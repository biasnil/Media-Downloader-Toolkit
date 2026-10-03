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

/** One video to download (a single link, or one item of a playlist). */
data class VideoEntry(val url: String, val title: String)

/** What's behind a link: one video, or a playlist of several. */
data class LinkInfo(val playlistTitle: String?, val entries: List<VideoEntry>)

/** Thin wrapper around youtubedl-android (bundled yt-dlp + Python + FFmpeg). */
object Downloader {
    private const val DOWNLOAD_ID = "download"
    private const val LIST_ID = "list"

    @Volatile private var ready = false
    @Volatile private var updateChecked = false
    @Volatile var cancelled = false

    /** True while any tab is downloading. One job at a time keeps things simple and polite to servers. */
    var busy by mutableStateOf(false)

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
     * Lists the video(s) behind a link without downloading anything — fast even for big
     * playlists. Keeps each item's real page link, so it works for any site, not just YouTube.
     */
    fun listEntries(url: String): LinkInfo {
        val request = YoutubeDLRequest(url).apply {
            addOption("--flat-playlist")
            addOption("--yes-playlist")
            addOption("--print", "%(webpage_url,url|)s\t%(playlist_title|)s\t%(title|)s")
        }
        val out = YoutubeDL.getInstance().execute(request, LIST_ID, null).out
        var playlistTitle: String? = null
        val entries = out.lines().filter { it.isNotBlank() }.map { line ->
            val parts = line.split('\t', limit = 3)
            val link = parts.getOrNull(0)?.trim().orEmpty()
            parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() && it != "NA" }?.let { playlistTitle = it }
            val title = parts.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() && it != "NA" } ?: "(untitled)"
            VideoEntry(if (link.startsWith("http")) link else url, title)
        }
        return LinkInfo(playlistTitle, entries)
    }

    /**
     * YouTube tab: downloads one video into [workDir] (app-private temp space) and returns
     * the finished file. [format] is "mp3" or "mp4"; [quality] is e.g. "320K" for mp3,
     * or "best"/"1080"/"720"... for mp4. Blocking — run on Dispatchers.IO.
     */
    fun downloadYouTube(
        entry: VideoEntry,
        format: String,
        quality: String,
        workDir: File,
        onProgress: (Float, Long, String) -> Unit,
    ): File {
        val request = YoutubeDLRequest(entry.url).apply {
            addOption("--no-playlist")
            addOption("-o", "${workDir.absolutePath}/%(title)s.%(ext)s")
            if (format == "mp3") {
                addOption("-x")
                addOption("--audio-format", "mp3")
                addOption("--audio-quality", quality)
            } else {
                // Prefer H.264 video + AAC audio so the MP4 plays on any phone
                addOption("-f", "bv*+ba/b")
                addOption("-S", if (quality == "best") "res,vcodec:h264,ext:mp4:m4a" else "res:$quality,vcodec:h264,ext:mp4:m4a")
                addOption("--merge-output-format", "mp4")
                addOption("--remux-video", "mp4")
            }
        }
        return runInto(request, workDir, format, isVideo = format == "mp4", onProgress).first()
    }

    /**
     * Other Sites tab: any link yt-dlp supports. A post can hold several videos
     * (e.g. an Instagram carousel), so this returns every file produced.
     */
    fun downloadAny(
        url: String,
        audioOnly: Boolean,
        workDir: File,
        onProgress: (Float, Long, String) -> Unit,
    ): List<File> {
        val request = YoutubeDLRequest(url).apply {
            addOption("-o", "${workDir.absolutePath}/%(uploader|Unknown)s - %(title).80s.%(ext)s")
            if (audioOnly) {
                addOption("-f", "bestaudio/best")
                addOption("-x")
                addOption("--audio-format", "mp3")
                addOption("--audio-quality", "320K")
            } else {
                // Same as desktop: H.264 + AAC first, since many sites default to VP9/AV1,
                // which plays or shares (e.g. WhatsApp) far less reliably
                addOption(
                    "-f",
                    "bestvideo[vcodec^=avc1][ext=mp4]+bestaudio[acodec^=mp4a][ext=m4a]/" +
                        "best[vcodec^=avc1][ext=mp4]/bestvideo+bestaudio/best"
                )
                addOption("--merge-output-format", "mp4")
                addOption("--remux-video", "mp4")
            }
        }
        return runInto(request, workDir, if (audioOnly) "mp3" else "mp4", isVideo = !audioOnly, onProgress)
    }

    private fun runInto(
        request: YoutubeDLRequest,
        workDir: File,
        ext: String,
        isVideo: Boolean,
        onProgress: (Float, Long, String) -> Unit,
    ): List<File> {
        workDir.deleteRecursively()
        workDir.mkdirs()
        request.addOption("--embed-metadata")
        request.addOption("--windows-filenames") // swaps out characters some phones reject in filenames

        // Settings (gear icon) — same options as desktop
        if (AppSettings.embedThumbnail) {
            request.addOption("--embed-thumbnail")
            request.addOption("--convert-thumbnails", "jpg") // YouTube serves .webp, which players ignore as cover art
        }
        if (AppSettings.downloadSubtitles && isVideo) {
            val langs = AppSettings.subtitleLangs.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            request.addOption("--write-subs")
            request.addOption("--sub-langs", langs.ifEmpty { listOf("en") }.joinToString(","))
            request.addOption("--embed-subs") // built into the MP4, so there's no separate file to keep track of
        }
        YoutubeDL.getInstance().execute(request, DOWNLOAD_ID, onProgress)
        val files = workDir.listFiles { f -> f.extension.equals(ext, true) }?.sortedBy { it.name }
        if (files.isNullOrEmpty()) throw IllegalStateException("Download finished but no .$ext file was produced")
        return files
    }

    fun cancel() {
        cancelled = true
        YoutubeDL.getInstance().destroyProcessById(LIST_ID)
        YoutubeDL.getInstance().destroyProcessById(DOWNLOAD_ID)
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

    /** Moves a finished file from temp space into the save folder, replacing any old copy. */
    fun save(context: Context, file: File, folder: Uri?) {
        if (folder == null) {
            defaultDir.mkdirs()
            val dest = File(defaultDir, file.name)
            file.copyTo(dest, overwrite = true)
            file.delete()
            // Makes it show up in music/video apps straight away
            MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), null, null)
        } else {
            val tree = DocumentFile.fromTreeUri(context, folder)
                ?: throw IllegalStateException("Can't open the chosen folder")
            tree.findFile(file.name)?.delete()
            val mime = if (file.extension.equals("mp3", true)) "audio/mpeg" else "video/mp4"
            val doc = tree.createFile(mime, file.name)
                ?: throw IllegalStateException("Can't create a file in the chosen folder")
            context.contentResolver.openOutputStream(doc.uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("Can't write to the chosen folder")
            file.delete()
        }
    }

    /** YouTube breaks yt-dlp often — this pulls the latest release without rebuilding the app. */
    fun updateYtDlp(context: Context): String {
        init(context)
        return when (YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext)) {
            YoutubeDL.UpdateStatus.DONE ->
                "yt-dlp updated to ${YoutubeDL.getInstance().versionName(context)}."
            YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "yt-dlp is already up to date."
            null -> "Couldn't check for updates."
        }
    }

    /** Checks for a newer yt-dlp once per app launch. Returns a log line, or null if skipped/failed. */
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
