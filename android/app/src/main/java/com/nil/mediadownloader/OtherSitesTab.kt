package com.nil.mediadownloader

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Sites known to work well. Only used for a friendlier log note on unrecognized links,
 * not an allow-list — yt-dlp supports 1,800+ sites, so anything else is still attempted.
 */
private val KNOWN_DOMAINS = listOf(
    "instagram.com", "x.com", "twitter.com", "reddit.com", "redd.it",
    "tiktok.com", "facebook.com", "fb.watch", "vimeo.com", "dailymotion.com",
    "soundcloud.com", "twitch.tv", "tumblr.com", "bilibili.com", "threads.net",
)

class OtherSitesState {
    var urlText by mutableStateOf("")
    val task = TaskState()
}

@Composable
fun OtherSitesTab(state: OtherSitesState, scope: CoroutineScope, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val task = state.task
    val prefs = remember { context.getSharedPreferences("other_sites_tab", Context.MODE_PRIVATE) }

    var audioOnly by remember { mutableStateOf(prefs.getBoolean("audio_only", false)) }
    var folderUri by remember { mutableStateOf(loadSavedFolder(context, prefs.getString("folder", null))) }
    val idle = !Downloader.busy

    fun startDownload() {
        val urls = state.urlText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (urls.isEmpty()) { task.status = "Paste at least one link."; return }
        val audio = audioOnly
        val folder = folderUri
        val workDir = File(context.cacheDir, "work")

        startJob(context, task)
        scope.launch {
            if (!prepare(context, task)) { endJob(context, task); return@launch }

            var done = 0; var failed = 0
            for ((i, url) in urls.withIndex()) {
                if (Downloader.cancelled) break
                val tag = "[${i + 1}/${urls.size}]"
                task.log.add("$tag $url")
                if (KNOWN_DOMAINS.none { it in url }) {
                    task.log.add("$tag Not one of the tested sites, trying anyway...")
                }
                task.status = "$tag Starting..."
                task.progress = 0f
                try {
                    val files = withRetries(task, tag) {
                        withContext(Dispatchers.IO) {
                            Downloader.downloadAny(url, audio, workDir, progressCallback(scope, task, tag))
                        }
                    }
                    withContext(Dispatchers.IO) { files.forEach { Downloader.save(context, it, folder) } }
                    files.forEach { task.log.add("$tag Done: ${it.name}") }
                    done += files.size
                } catch (e: YoutubeDL.CanceledException) {
                    task.log.add("$tag Cancelled")
                } catch (e: Exception) {
                    if (Downloader.cancelled) { task.log.add("$tag Cancelled"); break }
                    logFailure(context, task, tag, e)
                    failed++
                }
            }

            File(context.cacheDir, "work").deleteRecursively()
            task.status = if (Downloader.cancelled) "Cancelled. $done file(s) saved."
            else "Finished: $done file(s) saved, $failed link(s) failed."
            endJob(context, task)
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        SectionLabel("Link(s) — Instagram, X, Reddit, TikTok, and more")
        UrlBox(state.urlText, { state.urlText = it }, enabled = idle, placeholder = "One link per line")

        // Download as
        Column(
            Modifier
                .padding(top = 10.dp)
                .fillMaxWidth()
                .background(colors.surfaceVariant, RoundedCornerShape(8.dp))
                .padding(horizontal = 4.dp, vertical = 4.dp)
        ) {
            Text("Download as", color = colors.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(start = 12.dp, top = 4.dp))
            RadioRow("Video (mp4)", !audioOnly, idle) {
                audioOnly = false
                prefs.edit().putBoolean("audio_only", false).apply()
            }
            RadioRow("Audio only (mp3, 320 kbps)", audioOnly, idle) {
                audioOnly = true
                prefs.edit().putBoolean("audio_only", true).apply()
            }
        }

        SaveFolderRow(folderUri, enabled = idle) { uri ->
            folderUri = uri
            prefs.edit().apply { if (uri == null) remove("folder") else putString("folder", uri.toString()) }.apply()
        }

        ActionButtons(task, folderUri, onDownload = { startDownload() }, onCancel = {})
        ProgressAndLog(task, scope)
    }
}
