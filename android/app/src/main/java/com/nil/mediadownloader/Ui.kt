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
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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

/** One file in the progress ring and the Up next list. */
data class QueueItem(val title: String, val detail: String? = null, val thumbnail: String? = null)

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

    /** What's downloading now (the latest one to start) and what's still waiting. */
    var current by mutableStateOf<QueueItem?>(null)
    val upNext = mutableStateListOf<QueueItem>()

    fun queueItems(items: List<QueueItem>) {
        current = null
        upNext.clear()
        upNext.addAll(items)
    }

    fun begin(item: QueueItem) {
        upNext.remove(item)
        current = item
    }

    /** Swaps in a fuller [new] version of [old] (e.g. once a thumbnail is known). */
    fun replace(old: QueueItem, new: QueueItem) {
        if (current == old) current = new
    }

    fun end(item: QueueItem) {
        upNext.remove(item)
        if (current == item) current = null
    }

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
    task.queueItems(emptyList())
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

val PillShape = RoundedCornerShape(50)
val CardShape = RoundedCornerShape(18.dp)

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = modifier.padding(top = 14.dp, bottom = 6.dp))
}

/** A rounded card on the theme's card color. */
@Composable
fun AppCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(14.dp),
        content = content,
    )
}

/** Round icon button on a card-colored circle, for the header. */
@Composable
fun RoundIconButton(@DrawableRes icon: Int, description: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(44.dp),
        colors = IconButtonDefaults.iconButtonColors(containerColor = colors.surfaceContainer, contentColor = colors.onBackground),
    ) { Icon(painterResource(icon), contentDescription = description, modifier = Modifier.size(20.dp)) }
}

@Composable
fun SecondaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    @DrawableRes icon: Int? = null,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 44.dp),
        shape = PillShape,
        border = BorderStroke(1.dp, colors.outline),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        val tint = if (enabled) colors.onSurface else colors.onSurfaceVariant
        if (icon != null) {
            Icon(painterResource(icon), null, tint = tint, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = tint, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    @DrawableRes icon: Int? = null,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = PillShape,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
    ) {
        if (icon != null) {
            Icon(painterResource(icon), null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Small accent-colored text button, like the Paste / Clear / Report links on iOS. */
@Composable
fun SmallLink(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp)) {
        Text(
            text,
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** One pill in a row of choices (formats, qualities). */
@Composable
fun ChoiceChip(text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val faded = if (enabled) 1f else 0.5f
    Box(
        Modifier
            .heightIn(min = 40.dp)
            .clip(PillShape)
            .background(if (selected) colors.primary.copy(alpha = faded) else Color.Transparent)
            .border(1.dp, if (selected) Color.Transparent else colors.outline, PillShape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = (if (selected) colors.onPrimary else colors.onSurface).copy(alpha = faded),
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
        )
    }
}

/** Wrapping row of [ChoiceChip]s; reports the index picked. */
@Composable
fun ChipRow(options: List<String>, selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, option -> ChoiceChip(option, i == selected, enabled) { onSelect(i) } }
    }
}

/** Dropdown button showing options[selected]; reports the index picked. */
@Composable
fun Picker(selected: Int, options: List<String>, enabled: Boolean, onSelect: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { open = true },
            enabled = enabled,
            modifier = Modifier.heightIn(min = 40.dp),
            shape = PillShape,
            border = BorderStroke(1.dp, colors.outline),
            contentPadding = PaddingValues(start = 16.dp, end = 12.dp),
        ) {
            val tint = if (enabled) colors.onSurface else colors.onSurfaceVariant
            Text(options.getOrElse(selected) { options.first() }, color = tint, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(6.dp))
            Icon(painterResource(R.drawable.ic_chevron_down), null, tint = tint, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { onSelect(i); open = false })
            }
        }
    }
}

@Composable
fun CheckRow(text: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.padding(12.dp))
        Text(text, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun RadioRow(text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled, modifier = Modifier.padding(12.dp))
        Text(text)
    }
}

/** Info button: tap to read a longer explanation. */
@Composable
fun InfoButton(text: String) {
    var shown by remember { mutableStateOf(false) }
    IconButton(onClick = { shown = true }, modifier = Modifier.size(44.dp)) {
        Icon(painterResource(R.drawable.ic_info), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
    }
    if (shown) {
        AlertDialog(
            onDismissRequest = { shown = false },
            confirmButton = { TextButton(onClick = { shown = false }) { Text(stringResource(R.string.ok)) } },
            text = { Text(text) },
        )
    }
}

/** Settings section title with an info button at the end. */
@Composable
fun InfoHeader(title: String, info: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionLabel(title, Modifier.weight(1f))
        InfoButton(info)
    }
}

/** Row of pill tabs, scrolling sideways when they don't fit. [icons] go before the names. */
@Composable
fun PillTabs(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    icons: List<Int>? = null,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEachIndexed { i, name ->
            val isSelected = i == selected
            val fg = if (isSelected) colors.onPrimary else colors.onSurface
            Row(
                Modifier
                    .heightIn(min = 40.dp)
                    .clip(PillShape)
                    .background(if (isSelected) colors.primary else colors.surfaceContainer)
                    .selectable(selected = isSelected, role = Role.Tab) { onSelect(i) }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                icons?.getOrNull(i)?.let {
                    Icon(painterResource(it), null, tint = fg, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(name, color = fg, fontSize = 14.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold)
            }
        }
    }
}

/** A full-screen page with a back button and big title (History, Terms, Privacy...). [onClose] null = can't be closed. */
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
                        .heightIn(min = 60.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onClose != null) {
                        IconButton(onClick = onClose) {
                            Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.done))
                        }
                    } else {
                        Spacer(Modifier.width(16.dp))
                    }
                    Text(
                        title,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 22.sp,
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
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionLabel(label, Modifier.weight(1f))
        extra()
        TextButton(
            onClick = {
                val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
                val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                ClipboardWatcher.markSeen(context) // already pasted, so don't also offer it on return
                if (!text.isNullOrBlank()) onPaste(text.trim())
            },
            enabled = enabled,
            shape = PillShape,
            colors = ButtonDefaults.textButtonColors(containerColor = colors.surfaceContainer),
            contentPadding = PaddingValues(horizontal = 14.dp),
        ) {
            val tint = if (enabled) colors.primary else colors.onSurfaceVariant
            Icon(painterResource(R.drawable.ic_paste), null, tint = tint, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.paste), color = tint, fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
        leadingIcon = { Icon(painterResource(R.drawable.ic_link), null, modifier = Modifier.size(20.dp)) },
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = colors.surfaceContainer,
            unfocusedContainerColor = colors.surfaceContainer,
            disabledContainerColor = colors.surfaceContainer,
            unfocusedBorderColor = Color.Transparent,
            disabledBorderColor = Color.Transparent,
            focusedBorderColor = colors.primary,
        ),
    )
}

/** Format + Quality choices and "Skip files that already exist", saved to the tab's [prefs]. */
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

    SectionLabel(stringResource(R.string.label_format).removeSuffix(":"))
    ChipRow(formats.map { stringResource(it.first) }, formats.indexOfFirst { it.second == format }, enabled) { i ->
        format = formats[i].second
        prefs.edit().putString("format", format).apply()
    }
    SectionLabel(stringResource(R.string.label_quality).removeSuffix(":"))
    when (format) {
        "mp3" -> ChipRow(MP3_QUALITIES.map { it.first }, MP3_QUALITIES.indexOfFirst { it.first == mp3Quality }, enabled) { i ->
            mp3Quality = MP3_QUALITIES[i].first
            prefs.edit().putString("mp3_quality", mp3Quality).apply()
        }
        "mp4" -> ChipRow(
            MP4_QUALITIES.map { if (it.second == "best") bestAvailable else it.first },
            MP4_QUALITIES.indexOfFirst { it.first == mp4Quality },
            enabled,
        ) { i ->
            mp4Quality = MP4_QUALITIES[i].first
            prefs.edit().putString("mp4_quality", mp4Quality).apply()
        }
        "wav" -> Text(stringResource(R.string.lossless_wav), color = colors.onSurfaceVariant, fontSize = 14.sp)
        else -> Text(m4aNote, color = colors.onSurfaceVariant, fontSize = 14.sp)
    }
    Spacer(Modifier.height(4.dp))
    CheckRow(stringResource(R.string.skip_existing), skipExisting, enabled) {
        skipExisting = it
        prefs.edit().putBoolean("skip_existing", it).apply()
    }
}

/** "Save to" card with Browse... and a way back to the default folder. */
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
    AppCard(Modifier.padding(top = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_folder), null, tint = colors.onSurfaceVariant, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.save_to), color = colors.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(folderLabel(folder), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(8.dp))
            SecondaryButton(stringResource(R.string.browse), enabled = enabled) { picker.launch(null) }
        }
        if (folder != null) {
            TextButton(onClick = { onChange(null) }, enabled = enabled, contentPadding = PaddingValues(horizontal = 34.dp)) {
                Text(stringResource(R.string.use_default_folder), color = colors.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
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
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 54.dp),
        enabled = !task.running && !Downloader.updating,
        icon = R.drawable.ic_download,
        onClick = onDownload,
    )
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SecondaryButton(stringResource(R.string.cancel), Modifier.weight(1f), enabled = task.running, icon = R.drawable.ic_close, onClick = onCancel)
        SecondaryButton(stringResource(R.string.open_folder), Modifier.weight(1f), icon = R.drawable.ic_folder) { openFolder(context, folder) }
    }
}

/** True on tablets, where status and log show in a panel beside the tab instead of under it. */
val LocalWideLayout = compositionLocalOf { false }

/**
 * Status card with the progress bar, then Report / Clear / Update yt-dlp and the log, which folds away.
 * On tablets the tabs' own copy is hidden: [inPanel] = true draws the big version (ring, Cancel,
 * Up next, log open) for the Activity panel and the phone's Activity screen.
 */
@Composable
fun ProgressAndLog(
    task: TaskState,
    scope: CoroutineScope,
    showUpdate: Boolean = true,
    inPanel: Boolean = false,
    onCancel: (() -> Unit)? = null,
) {
    if (LocalWideLayout.current && !inPanel) return
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val logScroll = rememberScrollState()
    var report by remember { mutableStateOf<ProblemReport?>(null) }
    var logShown by rememberSaveable { mutableStateOf(inPanel) }
    LaunchedEffect(task.log.size, logScroll.maxValue, logShown) { logScroll.animateScrollTo(logScroll.maxValue) }
    report?.let { ReportProblemDialog(it) { report = null } }

    if (inPanel) {
        NowDownloading(task, onCancel)
        UpNext(task)
    } else {
        AppCard(Modifier.padding(top = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val current = task.current
                if (task.running) {
                    ProgressRing(current?.thumbnail, task.progress, 52.dp)
                } else {
                    Box(Modifier.size(8.dp).background(LocalPalette.current.success, PillShape))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    if (task.running && current != null) {
                        Text(current.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(task.status, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (task.running) colors.onSurfaceVariant else colors.onSurface)
                }
            }
            ProgressBar(task.progress, Modifier.padding(top = 12.dp))
        }
    }

    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { logShown = !logShown }, contentPadding = PaddingValues(horizontal = 4.dp)) {
            Text(
                stringResource(if (logShown) R.string.hide_log else R.string.view_log) + if (task.log.isEmpty()) "" else " (${task.log.size})",
                color = colors.onSurface,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
        }
        Spacer(Modifier.weight(1f))
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
    if (logShown) {
        Column(
            Modifier
                .fillMaxWidth()
                .height(if (inPanel) 360.dp else 220.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(LocalPalette.current.sidebar)
                .border(1.dp, colors.outline, RoundedCornerShape(14.dp))
                .verticalScroll(logScroll)
                .padding(12.dp)
        ) {
            task.log.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = colors.onSurfaceVariant) }
        }
    }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun ProgressBar(progress: Float, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    LinearProgressIndicator(
        progress = { progress },
        modifier = modifier.fillMaxWidth().height(6.dp).clip(PillShape),
        color = colors.primary,
        trackColor = colors.surfaceVariant,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

/** Round thumbnail with the progress drawn around it, like the mockup. */
@Composable
fun ProgressRing(thumbnail: String?, progress: Float, size: Dp) {
    val colors = MaterialTheme.colorScheme
    val stroke = size * 0.05f
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = stroke.toPx()
            val arcSize = Size(this.size.width - w, this.size.height - w)
            val topLeft = Offset(w / 2, w / 2)
            drawArc(colors.surfaceVariant, 0f, 360f, false, topLeft, arcSize, style = Stroke(w))
            drawArc(colors.primary, -90f, 360f * progress.coerceIn(0f, 1f), false, topLeft, arcSize, style = Stroke(w, cap = StrokeCap.Round))
        }
        Thumbnail(thumbnail, Modifier.fillMaxSize().padding(stroke * 2).clip(CircleShape))
    }
}

/** The big ring, title, status, progress bar and Cancel. */
@Composable
private fun NowDownloading(task: TaskState, onCancel: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    val current = task.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        ProgressRing(current?.thumbnail, task.progress, 184.dp)
        Text(
            current?.title ?: if (task.running) task.name else stringResource(R.string.nothing_downloading),
            fontSize = 18.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 16.dp),
        )
        current?.detail?.let {
            Text(it, color = colors.onSurfaceVariant, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
        Text(
            task.status,
            color = colors.onSurfaceVariant,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        ProgressBar(task.progress, Modifier.padding(top = 14.dp))
        if (onCancel != null) {
            FilledIconButton(
                onClick = onCancel,
                enabled = task.running,
                modifier = Modifier.padding(top = 16.dp).size(56.dp),
            ) { Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.cancel)) }
        }
    }
}

/** What's still waiting, under the ring. */
@Composable
private fun UpNext(task: TaskState) {
    if (task.upNext.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    val shown = task.upNext.take(8)
    Text(
        stringResource(R.string.up_next),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp,
        color = colors.onSurfaceVariant,
        modifier = Modifier.padding(top = 22.dp, bottom = 8.dp),
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        shown.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Thumbnail(item.thumbnail, Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(item.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    item.detail?.let { Text(it, fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
        if (task.upNext.size > shown.size) {
            Text(stringResource(R.string.more_count, task.upNext.size - shown.size), fontSize = 12.sp, color = colors.onSurfaceVariant)
        }
    }
}
