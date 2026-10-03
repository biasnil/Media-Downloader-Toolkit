package com.nil.mediadownloader

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// label shown in the UI to value passed to yt-dlp
private val MP3_QUALITIES = listOf("320 kbps" to "320K", "256 kbps" to "256K", "192 kbps" to "192K", "128 kbps" to "128K")
private val MP4_QUALITIES = listOf("Best available" to "best", "1080p" to "1080", "720p" to "720", "480p" to "480", "360p" to "360")

/** A playlist waiting for the user to tick which videos they want. */
class PlaylistPrompt(
    val title: String,
    val entries: List<VideoEntry>,
    val preselected: Set<Int>,
    val answer: CompletableDeferred<List<Int>?> = CompletableDeferred(),
)

/** Everything the YouTube tab needs to survive switching tabs mid-download. */
class YouTubeState {
    var urlText by mutableStateOf("")
    val task = TaskState()
    var playlistPrompt by mutableStateOf<PlaylistPrompt?>(null)
}

@Composable
fun YouTubeTab(state: YouTubeState, scope: CoroutineScope, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val task = state.task
    val prefs = remember { context.getSharedPreferences("youtube_tab", Context.MODE_PRIVATE) }

    // Settings, remembered between launches like config.json on desktop
    var format by remember { mutableStateOf(prefs.getString("format", "mp3") ?: "mp3") }
    var mp3Quality by remember { mutableStateOf(prefs.getString("mp3_quality", null) ?: MP3_QUALITIES[0].first) }
    var mp4Quality by remember { mutableStateOf(prefs.getString("mp4_quality", null) ?: MP4_QUALITIES[0].first) }
    var skipExisting by remember { mutableStateOf(prefs.getBoolean("skip_existing", true)) }
    var folderUri by remember { mutableStateOf(loadSavedFolder(context, prefs.getString("folder", null))) }

    val qualities = if (format == "mp3") MP3_QUALITIES else MP4_QUALITIES
    val qualityLabel = if (format == "mp3") mp3Quality else mp4Quality
    val idle = !Downloader.busy

    state.playlistPrompt?.let { prompt ->
        PlaylistDialog(
            prompt = prompt,
            onConfirm = { prompt.answer.complete(it) },
            onCancel = { prompt.answer.complete(null) },
        )
    }

    fun startDownload() {
        val urls = state.urlText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (urls.isEmpty()) { task.status = "Paste at least one YouTube link."; return }

        // Snapshot the settings so changing them mid-download can't mix things up
        val fmt = format
        val quality = qualities.firstOrNull { it.first == qualityLabel }?.second ?: qualities[0].second
        val skip = skipExisting
        val folder = folderUri
        val workDir = File(context.cacheDir, "work")

        startJob(context, task)
        scope.launch {
            if (!prepare(context, task)) { endJob(context, task); return@launch }
            val existing = try {
                withContext(Dispatchers.IO) { Downloader.existingNames(context, folder) }.toMutableSet()
            } catch (e: Exception) { mutableSetOf() }

            var done = 0; var skipped = 0; var failed = 0
            for ((i, url) in urls.withIndex()) {
                if (Downloader.cancelled) break
                val tag = "[${i + 1}/${urls.size}]"
                task.status = "$tag Reading link..."
                task.log.add("$tag $url")

                val info = try {
                    withRetries(task, tag) { withContext(Dispatchers.IO) { Downloader.listEntries(url) } }
                } catch (e: Exception) {
                    if (Downloader.cancelled) break
                    logFailure(context, task, tag, e)
                    failed++
                    continue
                }
                if (info.entries.isEmpty()) { task.log.add("$tag Nothing to download at this link."); failed++; continue }

                // Playlist: let the user tick which videos they want, like desktop
                val chosen = if (info.entries.size == 1) info.entries else {
                    val prompt = PlaylistPrompt(info.playlistTitle ?: "Playlist", info.entries, preselect(url, info.entries))
                    state.playlistPrompt = prompt
                    task.status = "$tag Waiting for playlist selection..."
                    val picked = prompt.answer.await()
                    state.playlistPrompt = null
                    if (picked == null) { task.log.add("$tag Playlist skipped."); continue }
                    task.log.add("$tag ${picked.size} of ${info.entries.size} videos selected from \"${prompt.title}\"")
                    picked.map { info.entries[it] }
                }

                for ((j, entry) in chosen.withIndex()) {
                    if (Downloader.cancelled) break
                    val label = if (chosen.size > 1) "$tag ${j + 1}/${chosen.size}" else tag
                    if (skip && Downloader.expectedFileName(entry.title, fmt) in existing) {
                        task.log.add("$label Skipped (already exists): ${entry.title}")
                        skipped++
                        continue
                    }
                    task.progress = 0f
                    try {
                        val file = withRetries(task, label) {
                            withContext(Dispatchers.IO) {
                                Downloader.downloadYouTube(entry, fmt, quality, workDir, progressCallback(scope, task, label))
                            }
                        }
                        // Backup check, in case the real file name differs from our guess
                        if (skip && file.name in existing) {
                            file.delete()
                            task.log.add("$label Skipped (already exists): ${file.name}")
                            skipped++
                            continue
                        }
                        withContext(Dispatchers.IO) { Downloader.save(context, file, folder) }
                        existing += file.name
                        task.log.add("$label Done: ${file.name}")
                        done++
                    } catch (e: YoutubeDL.CanceledException) {
                        task.log.add("$label Cancelled")
                    } catch (e: Exception) {
                        if (Downloader.cancelled) { task.log.add("$label Cancelled"); break }
                        logFailure(context, task, label, e)
                        failed++
                    }
                }
            }

            File(context.cacheDir, "work").deleteRecursively()
            task.status = if (Downloader.cancelled) "Cancelled. $done downloaded."
            else "Finished: $done downloaded, $skipped skipped, $failed failed."
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
        SectionLabel("YouTube URL(s), one per line")
        UrlBox(state.urlText, { state.urlText = it }, enabled = idle, placeholder = "https://youtu.be/...")

        SaveFolderRow(folderUri, enabled = idle) { uri ->
            folderUri = uri
            prefs.edit().apply { if (uri == null) remove("folder") else putString("folder", uri.toString()) }.apply()
        }

        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Format:")
            Spacer(Modifier.width(6.dp))
            Picker(format, listOf("mp3", "mp4"), enabled = idle) {
                format = it
                prefs.edit().putString("format", it).apply()
            }
            Spacer(Modifier.width(16.dp))
            Text("Quality:")
            Spacer(Modifier.width(6.dp))
            Picker(qualityLabel, qualities.map { it.first }, enabled = idle) {
                if (format == "mp3") { mp3Quality = it; prefs.edit().putString("mp3_quality", it).apply() }
                else { mp4Quality = it; prefs.edit().putString("mp4_quality", it).apply() }
            }
        }

        CheckRow("Skip files that already exist", skipExisting, idle) {
            skipExisting = it
            prefs.edit().putBoolean("skip_existing", it).apply()
        }

        ActionButtons(task, folderUri, onDownload = { startDownload() }, onCancel = {
            state.playlistPrompt?.answer?.complete(null)
        })
        ProgressAndLog(task, scope)
    }
}

/**
 * For a link like watch?v=ABC&list=..., pre-tick just that video (you usually wanted
 * that one song, not the whole mix). For a plain playlist link, pre-tick everything.
 */
private fun preselect(url: String, entries: List<VideoEntry>): Set<Int> {
    val wanted = videoIdOf(url) ?: return entries.indices.toSet()
    val idx = entries.indexOfFirst { videoIdOf(it.url) == wanted }
    return if (idx >= 0) setOf(idx) else entries.indices.toSet()
}

private fun videoIdOf(url: String): String? = try {
    val uri = Uri.parse(url)
    uri.getQueryParameter("v") ?: if (uri.host?.contains("youtu.be") == true) uri.lastPathSegment else null
} catch (e: Exception) { null }
