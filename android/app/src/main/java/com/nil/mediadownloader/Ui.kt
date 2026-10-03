package com.nil.mediadownloader

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------- Job state + helpers

/** Progress/status/log for one tab. Kept above the tabs so switching tabs doesn't lose it. */
class TaskState {
    var running by mutableStateOf(false)
    var progress by mutableFloatStateOf(0f)
    var status by mutableStateOf("Idle.")
    val log = mutableStateListOf<String>()
}

/** Call from the click handler (not inside launch) so a double tap can't start two jobs. */
fun startJob(context: Context, task: TaskState) {
    Downloader.busy = true
    Downloader.cancelled = false
    task.running = true
    task.progress = 0f
    if (AppSettings.backgroundDownloads) DownloadService.start(context.applicationContext, task)
}

fun endJob(context: Context, task: TaskState) {
    task.running = false
    task.progress = 0f
    Downloader.busy = false
    if (DownloadService.activeTask != null) DownloadService.finish(context.applicationContext, task.status)
}

/** Unpacks yt-dlp on first use and checks for a yt-dlp update once per launch. */
suspend fun prepare(context: Context, task: TaskState): Boolean = try {
    task.status = "Preparing (first launch takes a few seconds)..."
    withContext(Dispatchers.IO) { Downloader.init(context) }
    task.status = "Checking for yt-dlp updates..."
    withContext(Dispatchers.IO) { Downloader.autoUpdateOnce(context) }?.let { task.log.add(it) }
    true
} catch (e: Exception) {
    task.log.add("Startup failed: ${e.message}")
    task.status = "Couldn't start the downloader."
    false
}

/** The useful last "ERROR:" line from yt-dlp's output, instead of the whole dump. */
fun errorReason(e: Throwable): String =
    e.message?.lines()?.lastOrNull { "ERROR" in it }?.trim() ?: e.message ?: e.javaClass.simpleName

private val TRANSIENT = listOf("403", "429", "timed out", "Connection reset", "Temporary failure")

/** Runs [block], retrying up to 3 times on errors that usually go away on their own. */
suspend fun <T> withRetries(task: TaskState, label: String, block: suspend () -> T): T {
    var attempt = 1
    while (true) {
        try {
            return block()
        } catch (e: YoutubeDL.CanceledException) {
            throw e
        } catch (e: Exception) {
            if (Downloader.cancelled) throw YoutubeDL.CanceledException()
            val reason = errorReason(e)
            if (attempt < 3 && TRANSIENT.any { reason.contains(it, ignoreCase = true) }) {
                attempt++
                task.log.add("$label Temporary error, retrying ($attempt/3)...")
                delay(3000L * attempt)
                continue
            }
            throw e
        }
    }
}

/** Logs a failure, plus the FFmpeg diagnosis if that's what went wrong. */
suspend fun logFailure(context: Context, task: TaskState, label: String, e: Throwable) {
    task.log.add("$label Failed: ${errorReason(e)}")
    if (e.message?.contains("ffmpeg not found") == true) {
        task.log.add("FFmpeg check: " + withContext(Dispatchers.IO) { Downloader.ffmpegSelfTest(context) })
    }
}

/** Turns yt-dlp's progress callback into status/progress updates for [task]. */
fun progressCallback(scope: CoroutineScope, task: TaskState, label: String): (Float, Long, String) -> Unit =
    { p, eta, line ->
        scope.launch {
            val prefix = if (label.isEmpty()) "" else "$label "
            when {
                "[ExtractAudio]" in line -> task.status = "${prefix}Converting to MP3..."
                "[Merger]" in line || "[VideoRemuxer]" in line -> task.status = "${prefix}Finishing video..."
                p >= 0f -> {
                    task.progress = p / 100f
                    task.status = "$prefix${p.toInt()}%" + if (eta > 0) " · ETA ${eta}s" else ""
                }
            }
        }
    }

// ---------------------------------------------------------------- Folders

/** Restores a saved folder only if Android still lets us write there. */
fun loadSavedFolder(context: Context, saved: String?): Uri? {
    val uri = saved?.let(Uri::parse) ?: return null
    return uri.takeIf { u -> context.contentResolver.persistedUriPermissions.any { it.uri == u && it.isWritePermission } }
}

/** "primary:Music/YT Downloads" -> "Music/YT Downloads" */
fun folderLabel(uri: Uri?): String {
    if (uri == null) return "Download/MediaDownloader"
    return try {
        val id = DocumentsContract.getTreeDocumentId(uri)
        val path = id.substringAfter(':')
        if (id.startsWith("primary:")) path.ifEmpty { "Internal storage" } else "SD card/$path"
    } catch (e: Exception) { "Chosen folder" }
}

fun openFolder(context: Context, uri: Uri?) {
    val intent = if (uri == null) {
        Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
    } else {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(docUri, DocumentsContract.Document.MIME_TYPE_DIR)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app here can open that folder. Check it in the Files app.", Toast.LENGTH_LONG).show()
    }
}

// ---------------------------------------------------------------- Shared widgets

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, colors.outline),
    ) { Text(text, color = if (enabled) colors.onSurface else colors.onSurfaceVariant) }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier, shape = RoundedCornerShape(8.dp)) {
        Text(text, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun Picker(selected: String, options: List<String>, enabled: Boolean, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        SecondaryButton("$selected  ▾", enabled = enabled) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { onSelect(option); open = false })
            }
        }
    }
}

@Composable
fun CheckRow(text: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled)
        Text(text)
    }
}

@Composable
fun RadioRow(text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Text(text)
    }
}

@Composable
fun UrlBox(value: String, onChange: (String) -> Unit, enabled: Boolean, placeholder: String) {
    val colors = MaterialTheme.colorScheme
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(120.dp),
        placeholder = { Text(placeholder) },
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = colors.surfaceVariant,
            unfocusedContainerColor = colors.surfaceVariant,
            disabledContainerColor = colors.surfaceVariant,
            unfocusedBorderColor = colors.outline,
        ),
    )
}

/** "Save to" row with Browse... and a way back to the default folder. */
@Composable
fun SaveFolderRow(folder: Uri?, enabled: Boolean, onChange: (Uri?) -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            onChange(uri)
        }
    }
    SectionLabel("Save to")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            folderLabel(folder),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 14.sp,
            modifier = Modifier
                .weight(1f)
                .background(colors.surfaceVariant, RoundedCornerShape(8.dp))
                .padding(12.dp),
        )
        Spacer(Modifier.width(8.dp))
        SecondaryButton("Browse...", enabled = enabled) { picker.launch(null) }
    }
    if (folder != null) {
        TextButton(onClick = { onChange(null) }, enabled = enabled, contentPadding = PaddingValues(0.dp)) {
            Text("Use default folder", color = colors.primary, fontSize = 13.sp)
        }
    }
}

/** Download / Cancel / Open Folder. */
@Composable
fun ActionButtons(task: TaskState, folder: Uri?, onDownload: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    PrimaryButton(
        if (Downloader.busy && !task.running) "Another tab is downloading..." else "Download",
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        enabled = !Downloader.busy,
        onClick = onDownload,
    )
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton("Cancel", Modifier.weight(1f), enabled = task.running) {
            onCancel()
            Downloader.cancel()
            task.status = "Cancelling..."
        }
        SecondaryButton("Open Folder", Modifier.weight(1f)) { openFolder(context, folder) }
    }
}

/** Progress bar, status line, Update yt-dlp, and the log box. */
@Composable
fun ProgressAndLog(task: TaskState, scope: CoroutineScope) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val logScroll = rememberScrollState()
    LaunchedEffect(task.log.size, logScroll.maxValue) { logScroll.animateScrollTo(logScroll.maxValue) }

    LinearProgressIndicator(
        progress = { task.progress },
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
        color = colors.primary,
        trackColor = colors.surfaceVariant,
    )
    Text(task.status, color = colors.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionLabel("Log", Modifier.weight(1f))
        TextButton(
            enabled = !Downloader.busy,
            onClick = {
                Downloader.busy = true
                task.status = "Checking for yt-dlp update..."
                scope.launch {
                    task.status = try {
                        withContext(Dispatchers.IO) { Downloader.updateYtDlp(context) }
                    } catch (e: Exception) { "Update failed: ${e.message}" }
                    Downloader.busy = false
                }
            },
        ) { Text("Update yt-dlp", color = colors.primary, fontSize = 13.sp) }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .height(220.dp)
            .padding(bottom = 16.dp)
            .background(colors.surfaceVariant, RoundedCornerShape(8.dp))
            .verticalScroll(logScroll)
            .padding(10.dp)
    ) {
        task.log.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
    }
}
