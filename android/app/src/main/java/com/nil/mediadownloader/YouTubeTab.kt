package com.nil.mediadownloader

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Runs YouTube downloads and holds everything the tab shows, so it survives switching tabs mid-download. */
class YouTubeState {
    var urlText by mutableStateOf("")
    val task = TaskState(R.string.tab_youtube)
    var playlistPrompt by mutableStateOf<PlaylistPrompt?>(null)

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences("youtube_tab", Context.MODE_PRIVATE)

    /** The Download button: everything in the link box. */
    fun start(context: Context) {
        val urls = linesOf(urlText)
        if (urls.isEmpty()) { task.status = tr(R.string.yt_paste_link); return }
        start(context, urls)
    }

    /** A link shared from another app, the clipboard, or History. Waits its turn if a download is running. */
    fun enqueue(context: Context, link: String) {
        if (task.running) {
            task.queued += link
            task.addLog(tr(R.string.log_queued, link))
        } else {
            task.addLog(tr(R.string.log_link_received))
            start(context, listOf(link))
        }
    }

    fun cancel() {
        task.queued.clear()
        playlistPrompt?.answer?.complete(null)
        Downloader.cancel(task)
        if (task.running) task.status = tr(R.string.status_cancelling)
    }

    private fun start(context: Context, links: List<String>) {
        if (task.running) return
        val app = context.applicationContext
        startJob(app, task)
        AppState.scope.launch {
            var batch = links
            while (true) {
                // Read the settings now, so changing them mid-download can't mix things up
                run(app, batch, savedOptions(app, prefs(app), "mp3"))
                if (task.cancelled || task.queued.isEmpty()) break
                batch = task.queued.toList()
                task.queued.clear()
            }
            endJob(app, task)
        }
    }

    private suspend fun run(context: Context, urls: List<String>, o: DownloadOptions) {
        if (!prepare(context, task)) return
        val existing = try {
            withContext(Dispatchers.IO) { Downloader.existingNames(context, o.folder) }.toMutableSet()
        } catch (e: Exception) { mutableSetOf() }

        // 1. Work out every video first (playlists ask which videos you want)
        val jobs = mutableListOf<VideoEntry>()
        var failedLinks = 0
        for ((i, url) in urls.withIndex()) {
            if (task.cancelled) break
            val tag = "[${i + 1}/${urls.size}]"
            task.status = tr(R.string.status_reading_link, tag)
            task.addLog("$tag $url")

            val info = try {
                withRetries(task, tag) { withContext(Dispatchers.IO) { Downloader.listEntries(task, url) } }
            } catch (e: Exception) {
                if (task.cancelled) break
                logFailure(context, task, tag, e)
                HistoryStore.add(task.name, url, url, HistoryStatus.FAILED, detail = errorReason(e))
                failedLinks++
                continue
            }
            if (info.entries.isEmpty()) {
                task.addLog(tr(R.string.log_nothing_at_link, tag))
                HistoryStore.add(task.name, url, url, HistoryStatus.FAILED, detail = tr(R.string.nothing_at_link))
                failedLinks++
                continue
            }

            // Playlist: let the user tick which videos they want, like desktop
            if (info.entries.size == 1) {
                jobs += info.entries
            } else {
                val prompt = PlaylistPrompt(info.playlistTitle ?: tr(R.string.playlist_default), info.entries, preselect(url, info.entries))
                playlistPrompt = prompt
                task.status = tr(R.string.status_waiting_playlist, tag)
                val picked = prompt.answer.await()
                playlistPrompt = null
                if (picked == null) {
                    if (task.cancelled) break
                    task.addLog(tr(R.string.log_playlist_skipped, tag))
                    continue
                }
                task.addLog(tr(R.string.log_playlist_picked, tag, picked.size, info.entries.size, prompt.title))
                jobs += picked.map { info.entries[it] }
            }
        }

        // 2. Download them, several at once if set in Settings
        val tracker = BatchTracker(jobs.size).apply { failed = failedLinks }
        val workRoot = File(context.cacheDir, "work-youtube")
        if (jobs.isNotEmpty() && !task.cancelled) {
            val atOnce = o.atOnce.coerceAtMost(jobs.size)
            if (atOnce > 1) task.addLog(tr(R.string.log_at_once, atOnce))
            val label = { n: Int -> "[${n + 1}/${jobs.size}]" }
            tracker.show(task, label)
            task.queueItems(jobs.map { it.queueItem() })
            runLimited(task, jobs, atOnce) { n, entry ->
                download(context, entry, n, o, existing, tracker, File(workRoot, "$n"), label)
            }
        }

        workRoot.deleteRecursively()
        task.progress = 0f
        task.status = if (task.cancelled) tr(R.string.status_yt_cancelled, tracker.done)
        else tr(R.string.status_yt_finished, tracker.done, tracker.skipped, tracker.failed)
    }

    /** One video: skip check, download, save, history. */
    private suspend fun download(
        context: Context,
        entry: VideoEntry,
        n: Int,
        o: DownloadOptions,
        existing: MutableSet<String>,
        tracker: BatchTracker,
        workDir: File,
        label: (Int) -> String,
    ) {
        val tag = label(n)
        val item = entry.queueItem()
        // Skip before downloading anything, from the title
        if (o.skipExisting) {
            val name = Downloader.expectedFileName(entry.title, o.format)
            if (name in existing) {
                task.end(item)
                task.addLog(tr(R.string.log_skipped_exists, tag, entry.title))
                HistoryStore.add(task.name, entry.title, entry.url, HistoryStatus.SKIPPED, detail = tr(R.string.already_in_folder), fileName = name)
                tracker.skipped++
                tracker.finished++
                tracker.show(task, label)
                return
            }
        }

        tracker.active[n] = 0f to tr(R.string.starting)
        tracker.show(task, label)
        task.begin(item)
        try {
            val file = withRetries(task, tag) {
                withContext(Dispatchers.IO) {
                    Downloader.downloadYouTube(task, entry, o, workDir, progressCallback(AppState.scope) { f, text ->
                        tracker.update(task, n, f, text, label)
                    })
                }
            }
            // Backup check, in case the real file name differs from our guess
            if (o.skipExisting && file.name in existing) {
                file.delete()
                task.addLog(tr(R.string.log_skipped_exists, tag, file.name))
                HistoryStore.add(task.name, entry.title, entry.url, HistoryStatus.SKIPPED, detail = tr(R.string.already_in_folder), fileName = file.name)
                tracker.skipped++
                return
            }
            val saved = withContext(Dispatchers.IO) { Downloader.save(context, file, o.folder) }
            existing += file.name
            task.addLog(tr(R.string.log_done, tag, file.name))
            HistoryStore.add(task.name, file.nameWithoutExtension, entry.url, HistoryStatus.DONE, fileName = file.name, fileUri = saved)
            tracker.done++
        } catch (e: YoutubeDL.CanceledException) {
            task.addLog(tr(R.string.log_cancelled, tag))
            HistoryStore.add(task.name, entry.title, entry.url, HistoryStatus.CANCELLED)
        } catch (e: Exception) {
            if (task.cancelled) {
                task.addLog(tr(R.string.log_cancelled, tag))
                HistoryStore.add(task.name, entry.title, entry.url, HistoryStatus.CANCELLED)
            } else {
                logFailure(context, task, tag, e)
                HistoryStore.add(task.name, entry.title, entry.url, HistoryStatus.FAILED, detail = errorReason(e))
                tracker.failed++
            }
        } finally {
            task.end(item)
            tracker.active.remove(n)
            tracker.finished++
            tracker.show(task, label)
            workDir.deleteRecursively()
        }
    }
}

@Composable
fun YouTubeTab(state: YouTubeState, scope: CoroutineScope, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val task = state.task
    val prefs = remember { state.prefs(context) }
    var folderUri by remember { mutableStateOf(loadSavedFolder(context, prefs.getString("folder", null))) }
    val idle = !task.running

    Column(
        modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        UrlHeader(stringResource(R.string.yt_links_label), idle, onPaste = { state.urlText = appendLine(state.urlText, it) })
        UrlBox(state.urlText, { state.urlText = it }, enabled = idle, placeholder = "https://youtu.be/...")
        LinkCards(state.urlText, idle) { state.urlText = it }

        SaveFolderRow(folderUri, enabled = idle) { uri ->
            folderUri = uri
            prefs.edit().apply { if (uri == null) remove("folder") else putString("folder", uri.toString()) }.apply()
        }

        FormatOptions(prefs, "mp3", idle, m4aNote = stringResource(R.string.best_aac))

        ActionButtons(task, folderUri, onDownload = { state.start(context) }, onCancel = { state.cancel() })
        ProgressAndLog(task, scope)
    }
}

/**
 * For a link like watch?v=ABC&list=..., pre-tick just that video (you usually wanted
 * that one song, not the whole mix). For a plain playlist link, pre-tick everything.
 */
private fun preselect(url: String, entries: List<VideoEntry>): Set<Int> {
    val wanted = YouTubeLinks.videoId(url) ?: return entries.indices.toSet()
    val idx = entries.indexOfFirst { YouTubeLinks.videoId(it.url) == wanted }
    return if (idx >= 0) setOf(idx) else entries.indices.toSet()
}

/** For the progress ring; flat playlists sometimes leave out the thumbnail, so build it from the ID. */
private fun VideoEntry.queueItem() =
    QueueItem(title, detail, thumbnail ?: YouTubeLinks.videoId(url)?.let { YouTubeLinks.thumbnail(it) })

/** Recognizes YouTube links, for routing shared links and spotting them on the clipboard. */
object YouTubeLinks {
    fun isYouTube(link: String): Boolean {
        val host = try { Uri.parse(link.trim()).host?.lowercase() ?: "" } catch (e: Exception) { "" }
        return host.endsWith("youtube.com") || host == "youtu.be" || host.endsWith("youtube-nocookie.com")
    }

    fun videoId(url: String): String? = try {
        val uri = Uri.parse(url)
        val segments = uri.pathSegments
        uri.getQueryParameter("v")
            ?: when {
                uri.host?.contains("youtu.be") == true -> segments.firstOrNull()
                segments.size >= 2 && segments[0] in listOf("shorts", "live", "embed") -> segments[1]
                else -> null
            }
    } catch (e: Exception) { null }

    fun thumbnail(videoId: String) = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

    /** The 320×180 version of a YouTube thumbnail (about a third of the size), for small list rows. */
    fun smallThumbnail(url: String) =
        if ("i.ytimg.com/vi/" in url) url.replace(Regex("/(hq|sd|maxres)default\\.jpg"), "/mqdefault.jpg") else url

    fun playlistId(url: String): String? = try { Uri.parse(url).getQueryParameter("list") } catch (e: Exception) { null }
}
