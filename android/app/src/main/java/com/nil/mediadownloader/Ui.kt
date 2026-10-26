package com.nil.mediadownloader

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

// ---------------------------------------------------------------- Formats + options

// label shown in the UI to value passed to yt-dlp
val FORMATS = listOf(R.string.format_mp3 to "mp3", R.string.format_m4a to "m4a", R.string.format_wav to "wav", R.string.format_mp4 to "mp4")
/** Spotify is music only, so it offers just these. */
val MUSIC_FORMATS = listOf(R.string.format_mp3 to "mp3", R.string.format_wav to "wav")
// The labels here are also what's saved in settings, so they stay in English; "Best available" is translated when shown
val MP3_QUALITIES = listOf("320 kbps" to "320K", "256 kbps" to "256K", "192 kbps" to "192K", "128 kbps" to "128K")
val MP4_QUALITIES = listOf("Best available" to "best", "1080p" to "1080", "720p" to "720", "480p" to "480", "360p" to "360")

/** Settings captured when Download is pressed, so changing them mid-download can't mix things up. */
data class DownloadOptions(
    val format: String,      // "mp3", "m4a", "wav" or "mp4"
    val mp3Bitrate: String,  // e.g. "320K"
    val maxHeight: String,   // "best", or "1080", "720"...
    val skipExisting: Boolean,
    val folder: Uri?,
    val atOnce: Int,
)

fun savedFormat(prefs: SharedPreferences, defaultFormat: String, formats: List<Pair<Int, String>> = FORMATS): String =
    prefs.getString("format", null)?.takeIf { f -> formats.any { it.second == f } } ?: defaultFormat

/** A tab's saved choices (what its screen shows). Used by the Download button and for shared links. */
fun savedOptions(
    context: Context,
    prefs: SharedPreferences,
    defaultFormat: String,
    formats: List<Pair<Int, String>> = FORMATS,
): DownloadOptions {
    fun pick(list: List<Pair<String, String>>, key: String) =
        list.firstOrNull { it.first == prefs.getString(key, null) }?.second ?: list[0].second
    return DownloadOptions(
        format = savedFormat(prefs, defaultFormat, formats),
        mp3Bitrate = pick(MP3_QUALITIES, "mp3_quality"),
        maxHeight = pick(MP4_QUALITIES, "mp4_quality"),
        skipExisting = prefs.getBoolean("skip_existing", true),
        folder = loadSavedFolder(context, prefs.getString("folder", null)),
        atOnce = AppSettings.maxConcurrent,
    )
}

/** The non-empty lines of the link box. */
fun linesOf(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

/** Adds [text] to the link box on a new line. */
fun appendLine(box: String, text: String): String =
    box + (if (box.isEmpty() || box.endsWith("\n")) "" else "\n") + text

// ---------------------------------------------------------------- Job state + helpers

/** Progress/status/log for one tab. Kept above the tabs so switching tabs doesn't lose it. */
class TaskState(@param:StringRes private val nameRes: Int) {
    /** The tab's name, e.g. "YouTube" (for notifications and History). */
    val name: String get() = tr(nameRes)
    var running by mutableStateOf(false)
    var progress by mutableFloatStateOf(0f)
    var status by mutableStateOf(tr(R.string.idle))
    val log = mutableStateListOf<String>()
    /** Links shared from other apps while this tab was busy. They start right after. */
    val queued = mutableListOf<String>()
    @Volatile var cancelled = false
    /** yt-dlp processes running for this tab, so Cancel can stop all of them. */
    val processIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** FFmpeg processes the Utility tab is running, so Cancel can stop them too. */
    val nativeProcesses: MutableSet<Process> = ConcurrentHashMap.newKeySet()

    /** Adds a log line, keeping the last 500. */
    fun addLog(line: String) {
        log.add(line)
        if (log.size > 500) repeat(log.size - 500) { log.removeAt(0) }
    }
}

/** Call from the click handler (not inside launch) so a double tap can't start two jobs. */
fun startJob(context: Context, task: TaskState) {
    task.cancelled = false
    task.running = true
    task.progress = 0f
    if (AppSettings.backgroundDownloads) DownloadService.start(context.applicationContext, task)
}

fun endJob(context: Context, task: TaskState) {
    task.running = false
    task.progress = 0f
    DownloadService.finish(context.applicationContext, task)
}

/** Unpacks yt-dlp on first use and checks for a yt-dlp update once per launch. */
suspend fun prepare(context: Context, task: TaskState): Boolean = try {
    task.status = tr(R.string.status_preparing)
    withContext(Dispatchers.IO) { Downloader.init(context) }
    task.status = tr(R.string.status_checking_updates)
    withContext(Dispatchers.IO) { Downloader.autoUpdateOnce(context) }?.let { task.addLog(it) }
    true
} catch (e: Exception) {
    task.addLog(tr(R.string.log_startup_failed, e.message ?: ""))
    task.status = tr(R.string.status_cant_start)
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
            if (task.cancelled) throw YoutubeDL.CanceledException()
            val reason = errorReason(e)
            if (attempt < 3 && TRANSIENT.any { reason.contains(it, ignoreCase = true) }) {
                attempt++
                task.addLog(tr(R.string.log_retrying, label, attempt))
                delay(3000L * attempt)
                continue
            }
            throw e
        }
    }
}

/** Logs a failure, plus the FFmpeg diagnosis if that's what went wrong. */
suspend fun logFailure(context: Context, task: TaskState, label: String, e: Throwable) {
    task.addLog(tr(R.string.log_failed, label, errorReason(e)))
    if (e.message?.contains("ffmpeg not found") == true) {
        task.addLog(tr(R.string.log_ffmpeg_check, withContext(Dispatchers.IO) { Downloader.ffmpegSelfTest(context) }))
    }
}

/**
 * Turns yt-dlp's progress output into (fraction, status text) updates on the main thread.
 * The fraction is null for steps that don't report one (converting, merging).
 */
fun progressCallback(scope: CoroutineScope, onUpdate: (Float?, String) -> Unit): (Float, Long, String) -> Unit =
    { p, eta, line ->
        scope.launch {
            when {
                "[ExtractAudio]" in line -> onUpdate(null, tr(R.string.status_converting))
                "[Merger]" in line || "[VideoRemuxer]" in line -> onUpdate(null, tr(R.string.status_finishing_video))
                p >= 0f -> onUpdate(
                    p / 100f,
                    if (eta > 0) tr(R.string.status_percent_eta, p.toInt(), eta.toInt()) else tr(R.string.status_percent, p.toInt()),
                )
            }
        }
    }

/** Combines several files downloading at once into one progress bar and one status line. */
class BatchTracker(val total: Int) {
    var finished = 0
    var done = 0
    var skipped = 0
    var failed = 0
    /** Files in progress: job number to (fraction, what it's doing) */
    val active = mutableMapOf<Int, Pair<Float, String>>()

    private val overall: Float
        get() = if (total == 0) 0f else (finished + active.values.sumOf { it.first.toDouble() }.toFloat()) / total

    /** Updates the file's progress, unless it already finished (a late update can arrive after). */
    fun update(task: TaskState, index: Int, fraction: Float?, text: String, label: (Int) -> String) {
        val current = active[index] ?: return
        active[index] = (fraction ?: current.first) to text
        show(task, label)
    }

    fun show(task: TaskState, label: (Int) -> String) {
        task.progress = overall
        task.status = when {
            active.size == 1 -> active.entries.first().let { "${label(it.key)} ${it.value.second}" }
            active.size > 1 -> tr(R.string.status_at_once, active.size, finished, total)
            else -> tr(R.string.status_done_count, finished, total)
        }
    }
}

/** Runs [work] for every item, at most [limit] at a time. Stops starting new ones when cancelled. */
suspend fun <T> runLimited(task: TaskState, items: List<T>, limit: Int, work: suspend (Int, T) -> Unit) = coroutineScope {
    val slots = Semaphore(limit.coerceIn(1, 5))
    for ((i, item) in items.withIndex()) {
        slots.acquire()
        if (task.cancelled) {
            slots.release()
            break
        }
        launch {
            try { work(i, item) } finally { slots.release() }
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
        if (id.startsWith("primary:")) path.ifEmpty { tr(R.string.folder_internal) } else tr(R.string.folder_sd, path)
    } catch (e: Exception) { tr(R.string.folder_chosen) }
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
        Toast.makeText(context, tr(R.string.toast_no_folder_app), Toast.LENGTH_LONG).show()
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

/** Small red text button, like the Paste / Clear / Report links on iOS. */
@Composable
fun SmallLink(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp)) {
        Text(text, color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    }
}

/** Dropdown button showing options[selected]; reports the index picked. */
@Composable
fun Picker(selected: Int, options: List<String>, enabled: Boolean, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        SecondaryButton("${options.getOrElse(selected) { options.first() }}  ▾", enabled = enabled) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { onSelect(i); open = false })
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

/** ⓘ button: tap to read a longer explanation. */
@Composable
fun InfoButton(text: String) {
    var shown by remember { mutableStateOf(false) }
    TextButton(onClick = { shown = true }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(36.dp)) {
        Text("ⓘ", color = MaterialTheme.colorScheme.primary, fontSize = 18.sp)
    }
    if (shown) {
        AlertDialog(
            onDismissRequest = { shown = false },
            confirmButton = { TextButton(onClick = { shown = false }) { Text(stringResource(R.string.ok)) } },
            text = { Text(text) },
        )
    }
}

/** Settings section title with an ⓘ at the end. */
@Composable
fun InfoHeader(title: String, info: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionLabel(title, Modifier.weight(1f))
        InfoButton(info)
    }
}

/** Pill-style switcher, like the one under the desktop header. */
@Composable
fun PillTabs(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Row(
            Modifier
                .padding(horizontal = 8.dp)
                .horizontalScroll(rememberScrollState()) // scrolls sideways when the tabs don't fit
                .background(colors.surfaceVariant, RoundedCornerShape(8.dp))
                .padding(3.dp)
        ) {
            options.forEachIndexed { i, name ->
                val isSelected = i == selected
                Text(
                    name,
                    color = if (isSelected) Color.White else colors.onSurface,
                    fontSize = 14.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isSelected) YtRed else Color.Transparent)
                        .clickable { onSelect(i) }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** A full-screen page with a red title bar (History, Terms, Privacy...). [onClose] null = can't be closed. */
@Composable
fun FullScreenPage(
    title: String,
    onClose: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = { onClose?.invoke() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = onClose != null,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(YtRed)
                        .heightIn(min = 52.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onClose != null) {
                        TextButton(onClick = onClose) { Text(stringResource(R.string.done), color = Color.White, fontWeight = FontWeight.Bold) }
                    } else {
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(
                        title,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    actions()
                }
                Column(Modifier.weight(1f).fillMaxWidth()) { content() }
                bottomBar()
            }
        }
    }
}

/** Label above the link box, with a Paste button (and room for extras like the Sites menu). */
@Composable
fun UrlHeader(label: String, enabled: Boolean, onPaste: (String) -> Unit, extra: @Composable () -> Unit = {}) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionLabel(label, Modifier.weight(1f))
        extra()
        SmallLink(stringResource(R.string.paste), enabled = enabled) {
            val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
            val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
            ClipboardWatcher.markSeen(context) // already pasted, so don't also offer it on return
            if (!text.isNullOrBlank()) onPaste(text.trim())
        }
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

/** Format + Quality pickers and "Skip files that already exist", saved to the tab's [prefs]. */
@Composable
fun FormatOptions(
    prefs: SharedPreferences,
    defaultFormat: String,
    enabled: Boolean,
    m4aNote: String,
    formats: List<Pair<Int, String>> = FORMATS,
) {
    val colors = MaterialTheme.colorScheme
    var format by remember { mutableStateOf(savedFormat(prefs, defaultFormat, formats)) }
    var mp3Quality by remember { mutableStateOf(prefs.getString("mp3_quality", null) ?: MP3_QUALITIES[0].first) }
    var mp4Quality by remember { mutableStateOf(prefs.getString("mp4_quality", null) ?: MP4_QUALITIES[0].first) }
    var skipExisting by remember { mutableStateOf(prefs.getBoolean("skip_existing", true)) }
    val bestAvailable = stringResource(R.string.best_available)

    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.label_format), Modifier.width(72.dp))
        Picker(formats.indexOfFirst { it.second == format }, formats.map { stringResource(it.first) }, enabled) { i ->
            format = formats[i].second
            prefs.edit().putString("format", format).apply()
        }
    }
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.label_quality), Modifier.width(72.dp))
        when (format) {
            "mp3" -> Picker(MP3_QUALITIES.indexOfFirst { it.first == mp3Quality }, MP3_QUALITIES.map { it.first }, enabled) { i ->
                mp3Quality = MP3_QUALITIES[i].first
                prefs.edit().putString("mp3_quality", mp3Quality).apply()
            }
            "mp4" -> Picker(
                MP4_QUALITIES.indexOfFirst { it.first == mp4Quality },
                MP4_QUALITIES.map { if (it.second == "best") bestAvailable else it.first },
                enabled,
            ) { i ->
                mp4Quality = MP4_QUALITIES[i].first
                prefs.edit().putString("mp4_quality", mp4Quality).apply()
            }
            "wav" -> Text(stringResource(R.string.lossless_wav), color = colors.onSurfaceVariant)
            else -> Text(m4aNote, color = colors.onSurfaceVariant)
        }
    }
    CheckRow(stringResource(R.string.skip_existing), skipExisting, enabled) {
        skipExisting = it
        prefs.edit().putBoolean("skip_existing", it).apply()
    }
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
    SectionLabel(stringResource(R.string.save_to))
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
        SecondaryButton(stringResource(R.string.browse), enabled = enabled) { picker.launch(null) }
    }
    if (folder != null) {
        TextButton(onClick = { onChange(null) }, enabled = enabled, contentPadding = PaddingValues(0.dp)) {
            Text(stringResource(R.string.use_default_folder), color = colors.primary, fontSize = 13.sp)
        }
    }
}

/** Download / Cancel / Open Folder. The Utility tab says Start / Working... instead. */
@Composable
fun ActionButtons(
    task: TaskState,
    folder: Uri?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    @StringRes idleText: Int = R.string.download,
    @StringRes busyText: Int = R.string.downloading,
) {
    val context = LocalContext.current
    PrimaryButton(
        stringResource(
            when {
                task.running -> busyText
                Downloader.updating -> R.string.updating_ytdlp
                else -> idleText
            }
        ),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        enabled = !task.running && !Downloader.updating,
        onClick = onDownload,
    )
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton(stringResource(R.string.cancel), Modifier.weight(1f), enabled = task.running, onClick = onCancel)
        SecondaryButton(stringResource(R.string.open_folder), Modifier.weight(1f)) { openFolder(context, folder) }
    }
}

/** Progress bar, status line, Report / Clear / Update yt-dlp, and the log box. */
@Composable
fun ProgressAndLog(task: TaskState, scope: CoroutineScope, showUpdate: Boolean = true) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val logScroll = rememberScrollState()
    var report by remember { mutableStateOf<ProblemReport?>(null) }
    LaunchedEffect(task.log.size, logScroll.maxValue) { logScroll.animateScrollTo(logScroll.maxValue) }
    report?.let { ReportProblemDialog(it) { report = null } }

    LinearProgressIndicator(
        progress = { task.progress },
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
        color = colors.primary,
        trackColor = colors.surfaceVariant,
    )
    Text(task.status, color = colors.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionLabel(stringResource(R.string.log), Modifier.weight(1f))
        // The last lines of the log show what went wrong
        SmallLink(stringResource(R.string.report)) { report = ProblemReport(task.log.takeLast(30).joinToString("\n")) }
        SmallLink(stringResource(R.string.clear), enabled = !task.running && task.log.isNotEmpty()) { task.log.clear() }
        if (showUpdate) SmallLink(stringResource(R.string.update_ytdlp), enabled = !Downloader.busy) {
            Downloader.updating = true
            task.status = tr(R.string.status_checking_update)
            scope.launch {
                task.status = try {
                    withContext(Dispatchers.IO) { Downloader.updateYtDlp(context) }
                } catch (e: Exception) { tr(R.string.update_failed, e.message ?: "") }
                Downloader.updating = false
            }
        }
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
