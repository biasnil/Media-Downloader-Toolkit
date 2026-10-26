package com.nil.mediadownloader

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.yausername.youtubedl_android.YoutubeDL
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.pow
import kotlin.math.roundToInt

// The Utility tab's engine: runs the FFmpeg that ships with youtubedl-android directly,
// the same way the FFmpeg self-check in Downloader does.

/** A file picked with Android's file picker. */
data class PickedFile(val uri: Uri, val name: String) {
    val base: String get() = name.substringBeforeLast('.')
    val ext: String get() = name.substringAfterLast('.', "").lowercase()
}

/** What ffprobe says about a file. */
class MediaInfo(
    val duration: Double,          // seconds, 0 if unknown
    val bitrateKbps: Int,          // audio bitrate, 0 if unknown
    val sampleRate: Int,           // 0 if unknown
    val audioCodec: String?,       // "mp3", "aac", "alac"...
    val hasAudio: Boolean,
    val hasVideo: Boolean,         // a real video stream, not album art
    val hasCover: Boolean,         // album art (an attached picture)
    val coverCodec: String?,       // "mjpeg", "png"
    val tags: Map<String, String>, // lower-case keys: title, artist, album...
)

object Ffmpeg {
    private fun command(context: Context, binary: String, args: List<String>): ProcessBuilder {
        val exe = File(context.applicationInfo.nativeLibraryDir, binary)
        val packages = File(context.noBackupFilesDir, "youtubedl-android/packages")
        return ProcessBuilder(listOf(exe.absolutePath) + args).apply {
            environment()["LD_LIBRARY_PATH"] =
                "${packages.absolutePath}/python/usr/lib:${packages.absolutePath}/ffmpeg/usr/lib"
        }
    }

    /** Reads a stream on its own thread, keeping the last [keep] lines (so a full pipe can't stall FFmpeg). */
    private fun drain(stream: java.io.InputStream, keep: Int): Pair<Thread, ArrayDeque<String>> {
        val lines = ArrayDeque<String>()
        val t = thread(isDaemon = true) {
            stream.bufferedReader().forEachLine { line ->
                synchronized(lines) {
                    lines.addLast(line)
                    if (lines.size > keep) lines.removeFirst()
                }
            }
        }
        return t to lines
    }

    fun probe(context: Context, file: File): MediaInfo {
        val proc = command(
            context, "libffprobe.so",
            listOf("-v", "error", "-print_format", "json", "-show_format", "-show_streams", file.absolutePath),
        ).start()
        val (errThread, errLines) = drain(proc.errorStream, 20)
        val out = proc.inputStream.bufferedReader().use { it.readText() }
        val code = proc.waitFor()
        errThread.join(2000)
        if (code != 0) throw IllegalStateException(synchronized(errLines) { errLines.lastOrNull() } ?: "ffprobe failed")

        val json = JSONObject(out)
        val format = json.optJSONObject("format")
        val tags = mutableMapOf<String, String>()
        fun addTags(o: JSONObject?) {
            o?.optJSONObject("tags")?.let { t -> t.keys().forEach { k -> tags.putIfAbsent(k.lowercase(), t.optString(k)) } }
        }
        addTags(format)
        var audio: JSONObject? = null
        var hasVideo = false
        var cover: JSONObject? = null
        val streams = json.optJSONArray("streams")
        for (i in 0 until (streams?.length() ?: 0)) {
            val s = streams!!.optJSONObject(i) ?: continue
            when (s.optString("codec_type")) {
                "audio" -> if (audio == null) audio = s.also { addTags(it) } // OGG/Opus keep their tags here
                "video" -> if (s.optJSONObject("disposition")?.optInt("attached_pic") == 1) cover = s else hasVideo = true
            }
        }
        val bitrate = audio?.optString("bit_rate")?.toLongOrNull() ?: format?.optString("bit_rate")?.toLongOrNull() ?: 0L
        return MediaInfo(
            duration = format?.optString("duration")?.toDoubleOrNull() ?: 0.0,
            bitrateKbps = (bitrate / 1000).toInt(),
            sampleRate = audio?.optString("sample_rate")?.toIntOrNull() ?: 0,
            audioCodec = audio?.optString("codec_name"),
            hasAudio = audio != null,
            hasVideo = hasVideo,
            hasCover = cover != null && !hasVideo,
            coverCodec = cover?.optString("codec_name"),
            tags = tags,
        )
    }

    /**
     * Runs FFmpeg with [args], reporting progress against [durationSec] of output.
     * Returns FFmpeg's last log lines (the Normalizer reads its measurements from them).
     */
    fun run(context: Context, task: TaskState, args: List<String>, durationSec: Double, onProgress: (Float) -> Unit): String {
        if (task.cancelled) throw YoutubeDL.CanceledException()
        val proc = command(context, "libffmpeg.so", listOf("-hide_banner", "-nostdin", "-y", "-progress", "pipe:1", "-nostats") + args).start()
        task.nativeProcesses += proc
        try {
            val (errThread, errLines) = drain(proc.errorStream, 300)
            proc.inputStream.bufferedReader().forEachLine { line ->
                // out_time_us and (despite the name) out_time_ms are both microseconds
                if (line.startsWith("out_time_us=") || line.startsWith("out_time_ms=")) {
                    val us = line.substringAfter('=').toLongOrNull()
                    if (us != null && durationSec > 0) onProgress((us / 1_000_000.0 / durationSec).toFloat().coerceIn(0f, 1f))
                }
            }
            val code = proc.waitFor()
            errThread.join(2000)
            if (task.cancelled) throw YoutubeDL.CanceledException()
            val log = synchronized(errLines) { errLines.joinToString("\n") }
            if (code != 0) throw IllegalStateException(lastError(log))
            return log
        } finally {
            task.nativeProcesses -= proc
        }
    }

    /** The line that explains the failure, not FFmpeg's closing "Conversion failed!". */
    private fun lastError(log: String): String {
        val lines = log.lines().map { it.trim() }.filter { it.isNotEmpty() && it != "Conversion failed!" }
        return lines.lastOrNull { "rror" in it || "nvalid" in it || "not" in it } ?: lines.lastOrNull() ?: tr(R.string.err_ffmpeg_failed)
    }
}

// ---------------------------------------------------------------- File helpers

object PickedFiles {
    fun name(context: Context, uri: Uri): String =
        DocumentFile.fromSingleUri(context, uri)?.name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"

    /** Copies a picked file into app storage, since FFmpeg can only read real files. */
    fun copyIn(context: Context, uri: Uri, dir: File, name: String): File {
        dir.mkdirs()
        val file = File(dir, name)
        context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
            ?: throw IllegalStateException(tr(R.string.err_open_file))
        return file
    }

    /** Writes [file] over the picked original. Throws if the app isn't allowed to. */
    fun writeBack(context: Context, uri: Uri, file: File) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { out -> file.inputStream().use { it.copyTo(out) } }
            ?: throw IllegalStateException(tr(R.string.err_open_file))
    }
}

// ---------------------------------------------------------------- Tools

/** What one file came out as. */
sealed class ToolOutcome {
    /** [sameFormat]: it can replace the original (same file type). */
    class Made(val file: File, val sameFormat: Boolean) : ToolOutcome()
    class Skipped(val reason: String) : ToolOutcome()
}

/** Everything a Utility run needs, captured when Start is pressed. */
data class ToolJob(
    val tool: Tool,
    val files: List<PickedFile>,
    val replaceOriginal: Boolean,
    val targetLufs: Int,
    val convertTo: String,
    val convertKbps: Int,
    val coverMode: CoverMode,
    val coverImage: Uri?,
    val cropSquare: Boolean,
    val tags: Map<String, String>,
    val tagsFromName: Boolean,
    val trimStart: Double,
    val trimEnd: Double?,
    val fadeIn: Int,
    val fadeOut: Int,
    val speedMode: SpeedMode,
    val speed: Double,
    val semitones: Int,
    val bassBoost: Boolean,
    val volumeDb: Int,
    val trimSilence: Boolean,
)

object Tools {
    private val COVER_FORMATS = setOf("mp3", "m4a", "flac")
    private val EDIT_FORMATS = setOf("mp3", "m4a", "flac", "wav", "ogg", "opus")
    val TAG_KEYS = listOf("title", "artist", "album", "album_artist", "date", "genre", "track")

    /** Encoder settings for a sound format ("alac" = Apple Lossless in .m4a). */
    private fun audioCodec(format: String, kbps: Int): List<String> = when (format) {
        "mp3" -> listOf("-c:a", "libmp3lame", "-b:a", "${kbps.coerceIn(96, 320)}k")
        "m4a" -> listOf("-c:a", "aac", "-b:a", "${kbps.coerceIn(96, 320)}k")
        "alac" -> listOf("-c:a", "alac")
        "flac" -> listOf("-c:a", "flac")
        "wav" -> listOf("-c:a", "pcm_s16le")
        "ogg" -> listOf("-c:a", "libvorbis", "-b:a", "${kbps.coerceIn(96, 320)}k")
        "opus" -> listOf("-c:a", "libopus", "-b:a", "${kbps.coerceIn(64, 256)}k")
        else -> throw IllegalArgumentException(format)
    }

    private fun extensionOf(format: String) = if (format == "alac") "m4a" else format

    /** Tools that edit a song write the same format back (ALAC stays ALAC); unusual types become MP3. */
    private fun editFormat(file: PickedFile, info: MediaInfo): String = when {
        file.ext == "m4a" && info.audioCodec == "alac" -> "alac"
        file.ext == "aac" -> "m4a"
        file.ext in EDIT_FORMATS -> file.ext
        else -> "mp3"
    }

    /** Re-encoding at the original's bitrate (rounded up to a common one), 320 when unknown. */
    private fun sameQuality(info: MediaInfo): Int {
        val k = info.bitrateKbps
        return listOf(128, 160, 192, 256, 320).firstOrNull { it >= k - 8 }.takeIf { k > 0 } ?: 320
    }

    /** Keeps the song's tags, and its cover when the new file type can hold one. */
    private fun keepInfo(info: MediaInfo, ext: String): List<String> {
        val args = mutableListOf("-map", "0:a:0", "-map_metadata", "0")
        if (info.hasCover && ext in COVER_FORMATS) args += listOf("-map", "0:v:0", "-c:v", "copy", "-disposition:v:0", "attached_pic")
        if (ext == "mp3") args += listOf("-id3v2_version", "3")
        return args
    }

    private fun num(v: Double) = String.format(Locale.ROOT, "%.4f", v) // FFmpeg wants "1.5", never "1,5"

    /** Runs [job]'s tool on one file already copied to [input]. Blocking — run on Dispatchers.IO. */
    fun process(
        context: Context,
        task: TaskState,
        job: ToolJob,
        picked: PickedFile,
        input: File,
        dir: File,
        cover: File?,
        onProgress: (Float) -> Unit,
    ): ToolOutcome {
        val info = Ffmpeg.probe(context, input)
        val inPath = input.absolutePath
        fun output(suffix: String, ext: String) = File(dir, "${picked.base}$suffix.$ext")
        fun same(format: String) = extensionOf(format) == picked.ext

        if (job.tool != Tool.CONVERT && job.tool != Tool.COVER && job.tool != Tool.TAGS && !info.hasAudio) {
            throw IllegalStateException(tr(R.string.err_no_audio))
        }

        return when (job.tool) {
            Tool.NORMALIZE -> {
                val fmt = editFormat(picked, info)
                val target = "I=${job.targetLufs}:TP=-1.5:LRA=11"
                // Pass 1 measures, pass 2 applies exactly that (more accurate than one pass, and no clipping)
                val log = Ffmpeg.run(
                    context, task,
                    listOf("-i", inPath, "-map", "0:a:0", "-af", "loudnorm=$target:print_format=json", "-f", "null", "-"),
                    info.duration,
                ) { onProgress(it * 0.5f) }
                val stats = Regex("""\{[^{}]*"input_i"[^{}]*\}""").findAll(log).lastOrNull()?.value
                    ?.let { JSONObject(it) } ?: throw IllegalStateException(tr(R.string.err_measure))
                val measuredI = stats.optString("input_i")
                if (measuredI.contains("inf")) throw IllegalStateException(tr(R.string.err_silent))
                val rate = info.sampleRate.takeIf { it > 0 } ?: 44100
                val filter = "loudnorm=$target:measured_I=$measuredI:measured_TP=${stats.optString("input_tp")}" +
                    ":measured_LRA=${stats.optString("input_lra")}:measured_thresh=${stats.optString("input_thresh")}" +
                    ":offset=${stats.optString("target_offset")}:linear=true,aresample=$rate" // loudnorm works at 192 kHz
                val out = output(" (normalized)", extensionOf(fmt))
                Ffmpeg.run(
                    context, task,
                    listOf("-i", inPath) + keepInfo(info, out.extension) + listOf("-af", filter) + audioCodec(fmt, sameQuality(info)) + out.absolutePath,
                    info.duration,
                ) { onProgress(0.5f + it * 0.5f) }
                return ToolOutcome.Made(out, same(fmt))
            }

            Tool.CONVERT -> {
                val fmt = job.convertTo
                val ext = extensionOf(fmt)
                val out = output(if (ext == picked.ext) " (converted)" else "", ext)
                val args = when (fmt) {
                    "mp4" -> {
                        if (!info.hasVideo) throw IllegalStateException(tr(R.string.err_needs_video))
                        listOf(
                            "-i", inPath, "-map", "0:v:0", "-map", "0:a:0?", "-map_metadata", "0",
                            "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p",
                            "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart",
                        )
                    }
                    "mkv" -> listOf("-i", inPath, "-map", "0", "-c", "copy") // MKV holds almost anything, so no re-encoding
                    else -> {
                        if (!info.hasAudio) throw IllegalStateException(tr(R.string.err_no_audio))
                        val kbps = when (fmt) { "mp3" -> job.convertKbps; "opus" -> 160; "ogg" -> 192; else -> 256 }
                        listOf("-i", inPath) + keepInfo(info, ext) + audioCodec(fmt, kbps)
                    }
                }
                Ffmpeg.run(context, task, args + out.absolutePath, info.duration, onProgress)
                return ToolOutcome.Made(out, false)
            }

            Tool.COVER -> when (job.coverMode) {
                CoverMode.ADD -> {
                    if (picked.ext !in COVER_FORMATS) return ToolOutcome.Skipped(tr(R.string.skip_no_cover_format, picked.name))
                    val out = output(" (cover)", picked.ext)
                    val args = mutableListOf(
                        "-i", inPath, "-i", cover!!.absolutePath, "-map", "0:a:0", "-map", "1:v:0", "-map_metadata", "0",
                        "-c:a", "copy", "-c:v", "copy", "-disposition:v:0", "attached_pic",
                    )
                    if (picked.ext == "mp3") {
                        args += listOf("-id3v2_version", "3", "-metadata:s:v", "title=Album cover", "-metadata:s:v", "comment=Cover (front)")
                    }
                    Ffmpeg.run(context, task, args + out.absolutePath, info.duration, onProgress)
                    return ToolOutcome.Made(out, true)
                }
                CoverMode.REMOVE -> {
                    if (!info.hasCover) return ToolOutcome.Skipped(tr(R.string.skip_no_cover, picked.name))
                    val out = output(" (no cover)", picked.ext)
                    val args = listOf("-i", inPath, "-map", "0:a", "-map_metadata", "0", "-c", "copy") +
                        (if (picked.ext == "mp3") listOf("-id3v2_version", "3") else emptyList())
                    Ffmpeg.run(context, task, args + out.absolutePath, info.duration, onProgress)
                    return ToolOutcome.Made(out, true)
                }
                CoverMode.SAVE -> {
                    if (!info.hasCover) return ToolOutcome.Skipped(tr(R.string.skip_no_cover, picked.name))
                    val out = output(" (cover)", if (info.coverCodec == "png") "png" else "jpg")
                    Ffmpeg.run(context, task, listOf("-i", inPath, "-map", "0:v:0", "-c:v", "copy", "-frames:v", "1", "-update", "1", out.absolutePath), 0.0, onProgress)
                    return ToolOutcome.Made(out, false)
                }
            }

            Tool.TAGS -> {
                val values = job.tags.toMutableMap()
                if (job.tagsFromName && " - " in picked.base) {
                    values["artist"] = picked.base.substringBefore(" - ").trim()
                    values["title"] = picked.base.substringAfter(" - ").trim()
                }
                // One file: every field is sent, so emptying one removes it. Several: only filled-in ones
                val single = job.files.size == 1
                val metadata = TAG_KEYS.flatMap { key ->
                    val v = values[key].orEmpty().trim()
                    if (single || v.isNotEmpty()) listOf("-metadata", "$key=$v") else emptyList()
                }
                val out = output(" (tagged)", picked.ext)
                val args = listOf("-i", inPath, "-map", "0", "-map_metadata", "0", "-c", "copy") + metadata +
                    (if (picked.ext == "mp3") listOf("-id3v2_version", "3") else emptyList())
                Ffmpeg.run(context, task, args + out.absolutePath, info.duration, onProgress)
                return ToolOutcome.Made(out, true)
            }

            Tool.TRIM -> {
                val fmt = editFormat(picked, info)
                val end = job.trimEnd ?: info.duration
                val length = end - job.trimStart
                if (length <= 0) throw IllegalStateException(tr(R.string.err_trim_range))
                val fades = buildList {
                    if (job.fadeIn > 0) add("afade=t=in:st=0:d=${job.fadeIn}")
                    if (job.fadeOut > 0) add("afade=t=out:st=${num(maxOf(0.0, length - job.fadeOut))}:d=${job.fadeOut}")
                }
                val out = output(" (trimmed)", extensionOf(fmt))
                val args = listOf("-ss", num(job.trimStart), "-i", inPath, "-t", num(length)) + keepInfo(info, out.extension) +
                    (if (fades.isNotEmpty()) listOf("-af", fades.joinToString(",")) else emptyList()) + audioCodec(fmt, sameQuality(info))
                Ffmpeg.run(context, task, args + out.absolutePath, length, onProgress)
                return ToolOutcome.Made(out, same(fmt))
            }

            Tool.SPEED -> {
                val fmt = editFormat(picked, info)
                val rate = info.sampleRate.takeIf { it > 0 } ?: 44100
                val (filter, factor, suffix) = when (job.speedMode) {
                    // Slower and lower, with a short echo standing in for reverb
                    SpeedMode.SLOWED -> Triple("asetrate=${(rate * 0.85).roundToInt()},aresample=$rate,aecho=0.8:0.88:40|60|90:0.35|0.25|0.15", 0.85, " (slowed + reverb)")
                    SpeedMode.NIGHTCORE -> Triple("asetrate=${(rate * 1.25).roundToInt()},aresample=$rate", 1.25, " (nightcore)")
                    SpeedMode.SPEED -> Triple("atempo=${num(job.speed)}", job.speed, " (${job.speed.toString().removeSuffix(".0")}x)")
                    SpeedMode.PITCH -> Triple(
                        "rubberband=pitch=${num(2.0.pow(job.semitones / 12.0))}", 1.0,
                        " (${if (job.semitones > 0) "+" else ""}${job.semitones})",
                    )
                }
                val out = output(suffix, extensionOf(fmt))
                val args = listOf("-i", inPath) + keepInfo(info, out.extension) + listOf("-af", filter) + audioCodec(fmt, sameQuality(info))
                Ffmpeg.run(context, task, args + out.absolutePath, info.duration / factor, onProgress)
                return ToolOutcome.Made(out, same(fmt))
            }

            Tool.EFFECTS -> {
                val fmt = editFormat(picked, info)
                val filters = buildList {
                    if (job.trimSilence) {
                        // Silence at the end is removed by flipping the song, trimming its start, and flipping back
                        val cut = "silenceremove=start_periods=1:start_threshold=-50dB:start_silence=0.1"
                        add("$cut,areverse,$cut,areverse")
                    }
                    if (job.bassBoost) add("bass=g=8:f=110")
                    if (job.volumeDb != 0) add("volume=${job.volumeDb}dB")
                    if (job.bassBoost || job.volumeDb > 0) add("alimiter=limit=0.97") // louder, but no clipping
                }
                val out = output(" (edited)", extensionOf(fmt))
                val args = listOf("-i", inPath) + keepInfo(info, out.extension) + listOf("-af", filters.joinToString(",")) + audioCodec(fmt, sameQuality(info))
                Ffmpeg.run(context, task, args + out.absolutePath, info.duration, onProgress)
                return ToolOutcome.Made(out, same(fmt))
            }
        }
    }

    /** Turns any picture into a JPEG cover (optionally square, at most 1200 px wide). */
    fun prepareCover(context: Context, task: TaskState, picture: File, out: File, cropSquare: Boolean) {
        val crop = if (cropSquare) "crop=w='min(iw,ih)':h='min(iw,ih)'," else ""
        Ffmpeg.run(
            context, task,
            listOf("-i", picture.absolutePath, "-vf", "${crop}scale='min(1200,iw)':-2", "-frames:v", "1", "-q:v", "2", "-update", "1", out.absolutePath),
            0.0,
        ) {}
    }

    /** Reads a song's tags for the Tag editor (one file picked). */
    fun readTags(context: Context, file: File): Map<String, String> {
        val tags = Ffmpeg.probe(context, file).tags
        // MP4 files call the year "date" too, but some apps write "year"
        return TAG_KEYS.associateWith { key -> tags[key] ?: (if (key == "date") tags["year"] else null) ?: "" }
    }
}
