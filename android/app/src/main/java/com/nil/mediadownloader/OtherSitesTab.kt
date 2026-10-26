package com.nil.mediadownloader

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Sites known to work well. Only used for the Sites menu and a friendlier log note on unrecognized
 * links, not an allow-list — yt-dlp supports 1,800+ sites, so anything else is still attempted.
 */
private val KNOWN_SITES = listOf(
    "Instagram" to listOf("instagram.com"),
    "X / Twitter" to listOf("x.com", "twitter.com"),
    "Reddit" to listOf("reddit.com", "redd.it"),
    "TikTok" to listOf("tiktok.com"),
    "Facebook" to listOf("facebook.com", "fb.watch"),
    "Threads" to listOf("threads.net"),
    "Vimeo" to listOf("vimeo.com"),
    "Dailymotion" to listOf("dailymotion.com"),
    "SoundCloud" to listOf("soundcloud.com"),
    "Twitch" to listOf("twitch.tv"),
    "Tumblr" to listOf("tumblr.com"),
    "Bilibili" to listOf("bilibili.com"),
)
private val KNOWN_DOMAINS = KNOWN_SITES.flatMap { it.second }

/** One link to download, with the items ticked in a post that holds several (null = all of it). */
private class SiteJob(val url: String, val items: String?)

/** Runs Other Sites downloads and holds what that tab shows. */
class OtherSitesState {
    var urlText by mutableStateOf("")
    val task = TaskState(R.string.tab_other)
    /** A post with several videos, waiting for the user to tick which ones they want. */
    var picker by mutableStateOf<PlaylistPrompt?>(null)

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences("other_sites_tab", Context.MODE_PRIVATE)

    /** Before the format picker there was only an "Audio only" switch; keep what it was set to. */
    fun defaultFormat(prefs: SharedPreferences) = if (prefs.getBoolean("audio_only", false)) "mp3" else "mp4"

    /** The Download button: everything in the link box. */
    fun start(context: Context) {
        val urls = linesOf(urlText)
        if (urls.isEmpty()) { task.status = tr(R.string.other_paste_link); return }
        start(context, urls)
    }

    /** A link shared from another app or History. Waits its turn if a download is running. */
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
        picker?.answer?.complete(null)
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
                val prefs = prefs(app)
                run(app, batch, savedOptions(app, prefs, defaultFormat(prefs)))
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

        // 1. Look inside every link first: a post with several videos asks which ones you want
        val jobs = mutableListOf<SiteJob>()
        for ((i, url) in urls.withIndex()) {
            if (task.cancelled) break
            val tag = "[${i + 1}/${urls.size}]"
            task.status = tr(R.string.status_reading_link, tag)
            task.addLog("$tag $url")
            if (KNOWN_DOMAINS.none { it in url }) task.addLog(tr(R.string.log_untested_site, tag))
            val info = try {
                withContext(Dispatchers.IO) { Downloader.listEntries(task, url) }
            } catch (e: YoutubeDL.CanceledException) {
                break
            } catch (e: Exception) {
                if (task.cancelled) break
                // Some sites can only download, not list. That's fine: get the whole post
                jobs += SiteJob(url, null)
                continue
            }
            if (info.entries.size <= 1) {
                jobs += SiteJob(url, null)
                continue
            }
            val prompt = PlaylistPrompt(
                info.playlistTitle ?: tr(R.string.this_post), info.entries, info.entries.indices.toSet(),
                countText = R.string.picker_count_items,
            )
            picker = prompt
            task.status = tr(R.string.status_waiting_pick, tag)
            val picked = prompt.answer.await()
            picker = null
            if (picked == null) {
                if (task.cancelled) break
                task.addLog(tr(R.string.log_skipped, tag))
                continue
            }
            task.addLog(tr(R.string.log_items_picked, tag, picked.size, info.entries.size))
            // yt-dlp counts items from 1
            jobs += SiteJob(url, if (picked.size == info.entries.size) null else picked.joinToString(",") { "${it + 1}" })
        }

        // 2. Download them, several at once if set in Settings
        val tracker = BatchTracker(jobs.size)
        val workRoot = File(context.cacheDir, "work-other")
        if (jobs.isNotEmpty() && !task.cancelled) {
            val atOnce = o.atOnce.coerceAtMost(jobs.size)
            if (atOnce > 1) task.addLog(tr(R.string.log_at_once, atOnce))
            val label = { n: Int -> "[${n + 1}/${jobs.size}]" }
            tracker.show(task, label)
            task.queueItems(jobs.map { it.queueItem() })
            runLimited(task, jobs, atOnce) { n, job ->
                download(context, job, n, o, existing, tracker, File(workRoot, "$n"), label)
            }
        }

        workRoot.deleteRecursively()
        task.progress = 0f
        task.status = if (task.cancelled) tr(R.string.status_other_cancelled, tracker.done)
        else tr(R.string.status_other_finished, tracker.done, tracker.skipped, tracker.failed)
    }

    /** One link (which can hold several files): download, skip check, save, history. */
    private suspend fun download(
        context: Context,
        job: SiteJob,
        n: Int,
        o: DownloadOptions,
        existing: MutableSet<String>,
        tracker: BatchTracker,
        workDir: File,
        label: (Int) -> String,
    ) {
        val tag = label(n)
        val url = job.url
        val site = KNOWN_SITES.firstOrNull { (_, domains) -> domains.any { it in url } }?.first ?: task.name

        tracker.active[n] = 0f to tr(R.string.starting)
        tracker.show(task, label)
        val item = job.queueItem()
        task.begin(item)
        try {
            val files = withRetries(task, tag) {
                withContext(Dispatchers.IO) {
                    Downloader.downloadAny(task, url, job.items, o, workDir, progressCallback(AppState.scope) { f, text ->
                        tracker.update(task, n, f, text, label)
                    })
                }
            }
            for (file in files) {
                // yt-dlp only knows the file name once it has the post, so the skip check happens here
                if (o.skipExisting && file.name in existing) {
                    file.delete()
                    task.addLog(tr(R.string.log_skipped_exists, tag, file.name))
                    HistoryStore.add(site, file.nameWithoutExtension, url, HistoryStatus.SKIPPED, detail = tr(R.string.already_in_folder), fileName = file.name)
                    tracker.skipped++
                    continue
                }
                val saved = withContext(Dispatchers.IO) { Downloader.save(context, file, o.folder) }
                existing += file.name
                task.addLog(tr(R.string.log_done, tag, file.name))
                HistoryStore.add(site, file.nameWithoutExtension, url, HistoryStatus.DONE, fileName = file.name, fileUri = saved)
                tracker.done++
            }
        } catch (e: YoutubeDL.CanceledException) {
            task.addLog(tr(R.string.log_cancelled, tag))
            HistoryStore.add(site, url, url, HistoryStatus.CANCELLED)
        } catch (e: Exception) {
            if (task.cancelled) {
                task.addLog(tr(R.string.log_cancelled, tag))
                HistoryStore.add(site, url, url, HistoryStatus.CANCELLED)
            } else {
                logFailure(context, task, tag, e)
                HistoryStore.add(site, url, url, HistoryStatus.FAILED, detail = errorReason(e))
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

/** The site's name and the link; the preview card's thumbnail when one was found. */
private fun SiteJob.queueItem(): QueueItem {
    val site = KNOWN_SITES.firstOrNull { (_, domains) -> domains.any { it in url } }?.first
    return QueueItem(site ?: LinkPreviews.siteName(url), url, LinkPreviews.cached(url)?.thumbnail)
}

@Composable
fun OtherSitesTab(state: OtherSitesState, scope: CoroutineScope, modifier: Modifier = Modifier) {
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
        UrlHeader(
            stringResource(R.string.other_links_label),
            idle,
            onPaste = { state.urlText = appendLine(state.urlText, it) },
            extra = { SitesMenu() },
        )
        UrlBox(state.urlText, { state.urlText = it }, enabled = idle, placeholder = stringResource(R.string.other_placeholder))
        LinkCards(state.urlText, idle) { state.urlText = it }

        SaveFolderRow(folderUri, enabled = idle) { uri ->
            folderUri = uri
            prefs.edit().apply { if (uri == null) remove("folder") else putString("folder", uri.toString()) }.apply()
        }

        FormatOptions(prefs, state.defaultFormat(prefs), idle, m4aNote = stringResource(R.string.best_aac))

        ActionButtons(task, folderUri, onDownload = { state.start(context) }, onCancel = { state.cancel() })
        ProgressAndLog(task, scope)
    }
}

/** "Sites ▾": the sites known to work. */
@Composable
private fun SitesMenu() {
    val colors = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    Box {
        SmallLink(stringResource(R.string.sites_menu)) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                stringResource(R.string.tested_sites),
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            KNOWN_SITES.forEach { (name, _) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { open = false })
            }
            Text(
                stringResource(R.string.more_sites),
                color = colors.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
    }
}
