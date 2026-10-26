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

/**
 * One link to download, with the items ticked in a post that holds several (null = all of it).
 * [pictures] set: the link had no video, so these picture links are saved instead.
 */
private class SiteJob(
    val url: String,
    val items: String?,
    val pictures: List<String>? = null,
    val pictureSite: String? = null,
    val pictureTitle: String? = null,
)

/** Runs Other Sites downloads and holds what that tab shows. */
class OtherSitesState {
    var urlText by mutableStateOf("")
    val task = TaskState(R.string.tab_other)
    /** A post with several photos/videos, waiting for the user to tick which ones they want. */
    var picker by mutableStateOf<MediaPrompt?>(null)

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

        // 1. Look inside every link first: a post with several videos asks which ones you want,
        //    and a link with no video offers its pictures instead (like iOS)
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
                null
            }

            if (info != null && info.entries.isNotEmpty()) {
                if (info.entries.size == 1) {
                    jobs += SiteJob(url, null)
                    continue
                }
                val picked = ask(
                    MediaPrompt(
                        info.playlistTitle ?: tr(R.string.this_post),
                        info.entries.map { PickItem(it.title, it.thumbnail, isImage = false) },
                        info.entries.indices.toSet(),
                    ),
                    tag,
                ) ?: if (task.cancelled) break else continue
                task.addLog(tr(R.string.log_items_picked, tag, picked.size, info.entries.size))
                // yt-dlp counts items from 1
                jobs += SiteJob(url, if (picked.size == info.entries.size) null else picked.joinToString(",") { "${it + 1}" })
                continue
            }

            // yt-dlp found no video to list: look for pictures at the link instead
            task.addLog(tr(R.string.log_no_video_pictures, tag))
            val found = withContext(Dispatchers.IO) { try { PageImages.find(url) } catch (e: Exception) { null } }
            if (found == null) {
                task.addLog(tr(if (PageImages.isInstagramOrFacebook(url)) R.string.log_not_public else R.string.log_no_pictures, tag))
                // Still try downloading, so the log shows yt-dlp's own reason if that fails too
                jobs += SiteJob(url, null)
                continue
            }
            val chosen = if (found.images.size == 1) listOf(0) else {
                ask(
                    MediaPrompt(
                        found.title,
                        found.images.map { PickItem(found.title, it.url, isImage = true) },
                        found.images.indices.filter { found.images[it].preferred }.toSet(),
                    ),
                    tag,
                ) ?: if (task.cancelled) break else continue
            }
            task.addLog(tr(R.string.log_pictures_found, tag, chosen.size))
            jobs += SiteJob(url, null, chosen.map { found.images[it].url }, found.site, found.title)
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
            if (job.pictures != null) {
                savePictures(context, url, job.pictureSite ?: site, job.pictureTitle ?: site, job.pictures, n, o, existing, tracker, workDir, label)
                return
            }
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
            } else if (!(looksLikeNoVideo(e, o) && picturesInstead(context, url, n, o, existing, tracker, workDir, label))) {
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

    /** Shows the grid picker; null (and a log line) when skipped. */
    private suspend fun ask(prompt: MediaPrompt, tag: String): List<Int>? {
        picker = prompt
        task.status = tr(R.string.status_waiting_pick, tag)
        val picked = prompt.answer.await()
        picker = null
        if (picked == null && !task.cancelled) task.addLog(tr(R.string.log_skipped, tag))
        return picked
    }

    /**
     * yt-dlp listed the post but the download had no video in it (e.g. an Instagram photo in a carousel).
     * Saves the post's pictures instead, without asking (downloads may be running side by side).
     * Returns false when there were none, so the failure is reported as usual.
     */
    private suspend fun picturesInstead(
        context: Context, url: String, n: Int, o: DownloadOptions,
        existing: MutableSet<String>, tracker: BatchTracker, workDir: File, label: (Int) -> String,
    ): Boolean {
        val found = withContext(Dispatchers.IO) { try { PageImages.find(url) } catch (e: Exception) { null } } ?: return false
        val links = found.images.filter { it.preferred }.ifEmpty { found.images }.map { it.url }
        task.addLog(tr(R.string.log_saving_pictures_instead, label(n), links.size))
        try {
            savePictures(context, url, found.site, found.title, links, n, o, existing, tracker, workDir, label)
        } catch (c: YoutubeDL.CanceledException) {
            // Called from a catch block, so nothing above would catch this one
            task.addLog(tr(R.string.log_cancelled, label(n)))
        }
        return true
    }

    /** Downloads and saves pictures, one file each: "Instagram - user (2).jpg". */
    private suspend fun savePictures(
        context: Context, url: String, site: String, title: String, links: List<String>, n: Int, o: DownloadOptions,
        existing: MutableSet<String>, tracker: BatchTracker, workDir: File, label: (Int) -> String,
    ) {
        val tag = label(n)
        for ((k, link) in links.withIndex()) {
            if (task.cancelled) break
            val base = pictureName(if (links.size > 1) "$title (${k + 1})" else title)
            // The extension comes from the site, so compare names without it
            if (o.skipExisting && existing.any { it.substringBeforeLast('.') == base }) {
                task.addLog(tr(R.string.log_skipped_exists, tag, base))
                HistoryStore.add(site, base, url, HistoryStatus.SKIPPED, detail = tr(R.string.already_in_folder))
                tracker.skipped++
                continue
            }
            tracker.update(task, n, k.toFloat() / links.size, tr(R.string.status_picture, k + 1, links.size), label)
            try {
                val file = withContext(Dispatchers.IO) {
                    PageImages.download(task, link, url, workDir, base) { f ->
                        AppState.scope.launch { tracker.update(task, n, (k + f) / links.size, tr(R.string.status_picture, k + 1, links.size), label) }
                    }
                }
                val saved = withContext(Dispatchers.IO) { Downloader.save(context, file, o.folder) }
                existing += file.name
                task.addLog(tr(R.string.log_done, tag, file.name))
                HistoryStore.add(site, base, url, HistoryStatus.DONE, fileName = file.name, fileUri = saved)
                tracker.done++
            } catch (e: YoutubeDL.CanceledException) {
                throw e
            } catch (e: Exception) {
                if (task.cancelled) throw YoutubeDL.CanceledException()
                task.addLog(tr(R.string.log_failed, tag, "$base: ${e.message ?: e.javaClass.simpleName}"))
                HistoryStore.add(site, base, url, HistoryStatus.FAILED, detail = e.message)
                tracker.failed++
            }
        }
    }
}

/** yt-dlp's ways of saying there's nothing to download as a video at this link. */
private fun looksLikeNoVideo(e: Exception, o: DownloadOptions): Boolean {
    if (e.message == tr(R.string.err_no_file, o.format)) return true
    val reason = errorReason(e).lowercase()
    return listOf("no video", "unsupported url", "no media", "there is no video", "there's no video").any { it in reason }
}

/** A safe file name for a picture (no characters phones reject). */
private fun pictureName(text: String): String =
    text.replace(Regex("""[\\/:*?"<>|\n\r\t]"""), "_").replace(Regex("\\s+"), " ").trim().take(100).ifBlank { "picture" }

/** The site's name and the link; the preview card's thumbnail (or the first picture) when there is one. */
private fun SiteJob.queueItem(): QueueItem {
    val site = KNOWN_SITES.firstOrNull { (_, domains) -> domains.any { it in url } }?.first
    return QueueItem(pictureTitle ?: site ?: LinkPreviews.siteName(url), url, pictures?.firstOrNull() ?: LinkPreviews.cached(url)?.thumbnail)
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
