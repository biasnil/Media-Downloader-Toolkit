package com.nil.mediadownloader

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.system.Os
import android.system.OsConstants
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * One video to download (a single link, or one item of a playlist or post). [thumbnail] and
 * [detail] (a line under the title, e.g. "Artist · 3:28") only show in the pickers.
 */
data class VideoEntry(val url: String, val title: String, val thumbnail: String? = null, val detail: String? = null)

/** One YouTube search result. */
data class SearchResult(val id: String, val title: String, val seconds: Double)

/** What's behind a link: one video, or a playlist of several. */
data class LinkInfo(val playlistTitle: String?, val entries: List<VideoEntry>)

/** Thin wrapper around youtubedl-android (bundled yt-dlp + Python + FFmpeg). */
object Downloader {
    @Volatile private var ready = false
    @Volatile private var updateChecked = false
    private val nextId = AtomicInteger()

    /** True while yt-dlp is updating itself, which can't overlap a download. */
    var updating by mutableStateOf(false)

    /** True while anything runs (either tab, or a yt-dlp update). */
    val busy: Boolean get() = updating || AppState.anyRunning

    /** Used when no folder has been picked. Android 11+ lets apps write here without permissions. */
    val defaultDir: File
        get() = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "MediaDownloader"
        )

    /** Unpacks Python/yt-dlp/FFmpeg on first launch (a few seconds), instant afterwards. */
    @Synchronized
    fun init(context: Context) {
        if (ready) return
        YoutubeDL.getInstance().init(context.applicationContext)
        FFmpeg.getInstance().init(context.applicationContext)
        ready = true
    }

    /**
     * Runs yt-dlp under a fresh process ID that [task] remembers, so Cancel can stop
     * every file that tab is downloading at once.
     */
    private fun execute(task: TaskState, request: YoutubeDLRequest, callback: ((Float, Long, String) -> Unit)?): String {
        val id = "job-${nextId.incrementAndGet()}"
        task.processIds += id
        try {
            if (task.cancelled) throw YoutubeDL.CanceledException()
            return YoutubeDL.getInstance().execute(request, id, callback).out
        } finally {
            task.processIds -= id
        }
    }

    /**
     * Lists the video(s) behind a link without downloading anything — fast even for big
     * playlists. Keeps each item's real page link, so it works for any site, not just YouTube.
     */
    fun listEntries(task: TaskState, url: String): LinkInfo {
        val request = YoutubeDLRequest(url).apply {
            addOption("--flat-playlist")
            addOption("--yes-playlist")
            // Title last, so a tab inside it can't shift the other columns
            addOption("--print", "%(webpage_url,url|)s\t%(playlist_title|)s\t%(thumbnail|)s\t%(title|)s")
        }
        val out = execute(task, request, null)
        var playlistTitle: String? = null
        val entries = out.lines().filter { it.isNotBlank() }.map { line ->
            val parts = line.split('\t', limit = 4)
            val link = parts.getOrNull(0)?.trim().orEmpty()
            parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() && it != "NA" }?.let { playlistTitle = it }
            val entryUrl = if (link.startsWith("http")) link else url
            // Mixes and some playlists list no thumbnail; every YouTube video has one at a fixed address
            val thumbnail = parts.getOrNull(2)?.trim()?.takeIf { it.startsWith("http") }
                ?: YouTubeLinks.videoId(entryUrl)?.takeIf { YouTubeLinks.isYouTube(entryUrl) }?.let { YouTubeLinks.thumbnail(it) }
            val title = parts.getOrNull(3)?.trim()?.takeIf { it.isNotEmpty() && it != "NA" } ?: tr(R.string.untitled)
            VideoEntry(entryUrl, title, thumbnail)
        }
        return LinkInfo(playlistTitle, entries)
    }

    /** One YouTube search, without downloading anything: up to [count] results. */
    fun search(task: TaskState, query: String, count: Int): List<SearchResult> {
        val request = YoutubeDLRequest("ytsearch$count:$query").apply {
            addOption("--flat-playlist")
            addOption("--print", "%(id)s\t%(duration|)s\t%(title|)s")
        }
        return execute(task, request, null).lines().mapNotNull { line ->
            val parts = line.split('\t', limit = 3)
            val id = parts.getOrNull(0)?.trim().orEmpty()
            if (id.isEmpty() || id == "NA") null
            else SearchResult(id, parts.getOrNull(2)?.trim().orEmpty(), parts.getOrNull(1)?.trim()?.toDoubleOrNull() ?: 0.0)
        }
    }

    /** Audio formats pull out the sound and convert it. Returns false for video (mp4). */
    private fun YoutubeDLRequest.addAudioOptions(o: DownloadOptions): Boolean {
        when (o.format) {
            "mp3" -> {
                addOption("-f", "bestaudio/best")
                addOption("-x")
                addOption("--audio-format", "mp3")
                addOption("--audio-quality", o.mp3Bitrate)
            }
            "m4a" -> {
                // Sites usually serve AAC in .m4a, so this is a straight copy with no quality loss
                addOption("-f", "bestaudio[ext=m4a]/bestaudio/best")
                addOption("-x")
                addOption("--audio-format", "m4a")
            }
            "wav" -> {
                addOption("-f", "bestaudio/best")
                addOption("-x")
                addOption("--audio-format", "wav")
            }
            else -> return false
        }
        return true
    }

    /**
     * YouTube tab: downloads one video into [workDir] (app-private temp space) and returns
     * the finished file. Blocking — run on Dispatchers.IO.
     */
    fun downloadYouTube(
        task: TaskState,
        entry: VideoEntry,
        o: DownloadOptions,
        workDir: File,
        onProgress: (Float, Long, String) -> Unit,
    ): File {
        val request = YoutubeDLRequest(entry.url).apply {
            addOption("--no-playlist")
            addOption("-o", "${workDir.absolutePath}/%(title)s.%(ext)s")
            if (!addAudioOptions(o)) {
                // Prefer H.264 video + AAC audio so the MP4 plays on any phone
                addOption("-f", "bv*+ba/b")
                addOption("-S", if (o.maxHeight == "best") "res,vcodec:h264,ext:mp4:m4a" else "res:${o.maxHeight},vcodec:h264,ext:mp4:m4a")
                addOption("--merge-output-format", "mp4")
                addOption("--remux-video", "mp4")
            }
        }
        return runInto(task, request, workDir, o, onProgress).first()
    }

    /**
     * Other Sites tab: any link yt-dlp supports. A post can hold several videos
     * (e.g. an Instagram carousel), so this returns every file produced. [items] is
     * yt-dlp's --playlist-items (e.g. "1,3,4") when only some of them were ticked.
     */
    fun downloadAny(
        task: TaskState,
        url: String,
        items: String?,
        o: DownloadOptions,
        workDir: File,
        onProgress: (Float, Long, String) -> Unit,
    ): List<File> {
        val request = YoutubeDLRequest(url).apply {
            addOption("-o", "${workDir.absolutePath}/%(uploader|Unknown)s - %(title).80s.%(ext)s")
            items?.let { addOption("--playlist-items", it) }
            if (!addAudioOptions(o)) {
                // Same as desktop: H.264 + AAC first, since many sites default to VP9/AV1,
                // which plays or shares (e.g. WhatsApp) far less reliably. "?" keeps formats
                // whose height the site doesn't say.
                val h = if (o.maxHeight == "best") "" else "[height<=?${o.maxHeight}]"
                addOption(
                    "-f",
                    "bestvideo[vcodec^=avc1][ext=mp4]$h+bestaudio[acodec^=mp4a][ext=m4a]/" +
                        "best[vcodec^=avc1][ext=mp4]$h/bestvideo$h+bestaudio/best$h/best"
                )
                addOption("--merge-output-format", "mp4")
                addOption("--remux-video", "mp4")
            }
        }
        return runInto(task, request, workDir, o, onProgress)
    }

    /**
     * Spotify tab: downloads the matched YouTube video's sound as "[fileBase].mp3/.wav",
     * tagged with Spotify's own title and artist rather than the YouTube video's.
     */
    fun downloadSpotify(
        task: TaskState,
        videoUrl: String,
        fileBase: String,
        title: String,
        artist: String,
        o: DownloadOptions,
        workDir: File,
        onProgress: (Float, Long, String) -> Unit,
    ): File {
        val request = YoutubeDLRequest(videoUrl).apply {
            addOption("--no-playlist")
            addOption("-o", "${workDir.absolutePath}/${fileBase.replace("%", "%%")}.%(ext)s")
            addAudioOptions(o)
            // meta_ fields go straight into the file's tags (--embed-metadata is added below)
            addOption("--parse-metadata", "${metadataText(title)}:%(meta_title)s")
            if (artist.isNotBlank()) addOption("--parse-metadata", "${metadataText(artist)}:%(meta_artist)s")
        }
        return runInto(task, request, workDir, o, onProgress).first()
    }

    /** Plain text for the left side of --parse-metadata, which is a template: escape % and the ":" separator. */
    private fun metadataText(text: String) = text.replace("%", "%%").replace(":", "\\:")

    private fun runInto(
        task: TaskState,
        request: YoutubeDLRequest,
        workDir: File,
        o: DownloadOptions,
        onProgress: (Float, Long, String) -> Unit,
    ): List<File> {
        workDir.deleteRecursively()
        workDir.mkdirs()
        request.addOption("--embed-metadata")
        request.addOption("--windows-filenames") // swaps out characters some phones reject in filenames
        rateFor(o.atOnce)?.let { request.addOption("--limit-rate", it) }

        // Settings (gear icon) — same options as desktop
        if (AppSettings.embedThumbnail && o.format != "wav") { // WAV files can't hold cover art
            request.addOption("--embed-thumbnail")
            request.addOption("--convert-thumbnails", "jpg") // YouTube serves .webp, which players ignore as cover art
        }
        if (AppSettings.downloadSubtitles && o.format == "mp4") {
            val langs = AppSettings.subtitleLangs.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            request.addOption("--write-subs")
            request.addOption("--sub-langs", langs.ifEmpty { listOf("en") }.joinToString(","))
            request.addOption("--embed-subs") // built into the MP4, so there's no separate file to keep track of
        }
        execute(task, request, onProgress)
        val files = workDir.listFiles { f -> f.extension.equals(o.format, true) }?.sortedBy { it.name }
        if (files.isNullOrEmpty()) throw IllegalStateException(tr(R.string.err_no_file, o.format))
        return files
    }

    /**
     * yt-dlp's --limit-rate for one file, so every file downloading at once (in both tabs)
     * stays under Settings → Speed limit in total. null = unlimited.
     */
    private fun rateFor(atOnce: Int): String? {
        val kb = AppSettings.speedLimitKB
        if (kb <= 0) return null
        val runningTabs = AppState.tasks.count { it.running }.coerceAtLeast(1)
        return "${(kb / (atOnce.coerceAtLeast(1) * runningTabs)).coerceAtLeast(16)}K"
    }

    /** Stops everything [task] is running and stops it starting more. */
    fun cancel(task: TaskState) {
        task.cancelled = true
        task.processIds.toList().forEach { YoutubeDL.getInstance().destroyProcessById(it) }
        task.nativeProcesses.toList().forEach { it.destroy() }
    }

    /** Guesses the file name yt-dlp will produce (with --windows-filenames), for skip checks. */
    fun expectedFileName(title: String, ext: String): String {
        val swaps = mapOf(
            '/' to '⧸', '\\' to '⧹', ':' to '：', '*' to '＊', '?' to '？',
            '"' to '＂', '<' to '＜', '>' to '＞', '|' to '｜',
        )
        val clean = title.map { swaps[it] ?: it }.joinToString("").trim().trimEnd('.')
        return "$clean.$ext"
    }

    /** Names of files already in the save folder (default folder when [folder] is null). */
    fun existingNames(context: Context, folder: Uri?): Set<String> =
        if (folder == null) defaultDir.list()?.toSet() ?: emptySet()
        else DocumentFile.fromTreeUri(context, folder)?.listFiles()?.mapNotNull { it.name }?.toSet() ?: emptySet()

    /**
     * Moves a finished file from temp space into the save folder, replacing any old copy.
     * Returns where it ended up (file:// or content://), for History.
     */
    fun save(context: Context, file: File, folder: Uri?): String {
        if (folder == null) {
            defaultDir.mkdirs()
            val dest = File(defaultDir, file.name)
            file.copyTo(dest, overwrite = true)
            file.delete()
            // Makes it show up in music/video apps straight away
            MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), null, null)
            return Uri.fromFile(dest).toString()
        } else {
            val tree = DocumentFile.fromTreeUri(context, folder)
                ?: throw IllegalStateException(tr(R.string.err_open_folder))
            tree.findFile(file.name)?.delete()
            val doc = tree.createFile(SavedFiles.mimeType(file.name), file.name)
                ?: throw IllegalStateException(tr(R.string.err_create_file))
            context.contentResolver.openOutputStream(doc.uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException(tr(R.string.err_write_folder))
            file.delete()
            return doc.uri.toString()
        }
    }

    /** YouTube breaks yt-dlp often — this pulls the latest release without rebuilding the app. */
    fun updateYtDlp(context: Context): String {
        init(context)
        return when (YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext)) {
            YoutubeDL.UpdateStatus.DONE ->
                tr(R.string.ytdlp_updated, YoutubeDL.getInstance().versionName(context) ?: "")
            YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> tr(R.string.ytdlp_up_to_date)
            null -> tr(R.string.ytdlp_check_failed)
        }
    }

    /**
     * Checks for a newer yt-dlp once per app launch. Returns a log line, or null if skipped/failed.
     * Synchronized so a second tab starting at the same time waits instead of running mid-update.
     */
    @Synchronized
    fun autoUpdateOnce(context: Context): String? {
        if (updateChecked) return null
        updateChecked = true
        return try { updateYtDlp(context) } catch (e: Exception) { null }
    }

    /**
     * Runs the bundled ffmpeg directly (same way yt-dlp does) and reports what actually
     * happens, since yt-dlp only says "not found" even when ffmpeg exists but won't start.
     */
    fun ffmpegSelfTest(context: Context): String {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val ffmpeg = File(nativeDir, "libffmpeg.so")
        val packages = File(context.noBackupFilesDir, "youtubedl-android/packages")
        val info = "ABI=${Build.SUPPORTED_ABIS.firstOrNull()}, " +
            "page size=${Os.sysconf(OsConstants._SC_PAGESIZE) / 1024}KB, API=${Build.VERSION.SDK_INT}"
        if (!ffmpeg.exists()) {
            val found = nativeDir.list()?.joinToString() ?: "nothing"
            return "ffmpeg binary missing. Native libs present: $found. ($info)"
        }
        return try {
            val pb = ProcessBuilder(ffmpeg.absolutePath, "-version").redirectErrorStream(true)
            pb.environment()["LD_LIBRARY_PATH"] =
                "${packages.absolutePath}/python/usr/lib:${packages.absolutePath}/ffmpeg/usr/lib"
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            val code = proc.waitFor()
            if (code == 0) "ffmpeg runs fine: ${out.lineSequence().first()} ($info)"
            else "ffmpeg failed to start (exit $code): ${out.take(300)} ($info)"
        } catch (e: Exception) {
            "ffmpeg couldn't be launched: ${e.message} ($info)"
        }
    }
}
