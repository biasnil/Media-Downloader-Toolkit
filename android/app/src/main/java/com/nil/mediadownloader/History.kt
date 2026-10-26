package com.nil.mediadownloader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.UUID

// ---------------------------------------------------------------- Model

enum class HistoryStatus { DONE, SKIPPED, FAILED, CANCELLED }

data class HistoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val date: Long = System.currentTimeMillis(),
    val source: String,          // "YouTube", "Instagram", "Spotify"...
    val title: String,
    val link: String,
    val status: HistoryStatus,
    val detail: String? = null,  // why it failed / was skipped
    val fileName: String? = null,
    val fileUri: String? = null, // the saved file (file:// or content://), for "done"
)

// ---------------------------------------------------------------- Store

/** Every download attempt, newest first. Saved privately (not in the download folder), up to 1,000 entries. */
object HistoryStore {
    val entries = mutableStateListOf<HistoryEntry>()
    private var file: File? = null
    @Volatile private var latest: List<HistoryEntry> = emptyList()
    private val writeLock = Any()

    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, "history.json")
        file = f
        try {
            if (f.exists()) entries.addAll(fromJson(f.readText()))
        } catch (e: Exception) {
            // A damaged file just means starting with an empty history
        }
    }

    fun add(
        source: String, title: String, link: String, status: HistoryStatus,
        detail: String? = null, fileName: String? = null, fileUri: String? = null,
    ) {
        entries.add(0, HistoryEntry(
            source = source, title = title.ifBlank { link }, link = link, status = status,
            detail = detail, fileName = fileName, fileUri = fileUri,
        ))
        while (entries.size > 1000) entries.removeAt(entries.lastIndex)
        save()
    }

    fun remove(ids: Collection<String>) {
        val set = ids.toSet()
        entries.removeAll(entries.filter { it.id in set })
        save()
    }

    fun removeWhere(predicate: (HistoryEntry) -> Boolean) {
        entries.removeAll(entries.filter(predicate))
        save()
    }

    /** Deletes the saved files of these entries (if still there) and removes the entries. */
    fun deleteFiles(context: Context, ids: Collection<String>) {
        val set = ids.toSet()
        entries.filter { it.id in set }.forEach { e -> e.fileUri?.let { SavedFiles.delete(context, it) } }
        remove(set)
    }

    private fun save() {
        latest = entries.toList()
        val target = file ?: return
        AppState.scope.launch(Dispatchers.IO) {
            // Always writes the newest list, whichever save gets here first
            synchronized(writeLock) {
                try {
                    val tmp = File(target.path + ".tmp")
                    tmp.writeText(toJson(latest))
                    tmp.renameTo(target)
                } catch (e: Exception) {
                }
            }
        }
    }

    private fun toJson(list: List<HistoryEntry>): String = JSONArray().apply {
        list.forEach { e ->
            put(JSONObject().apply {
                put("id", e.id)
                put("date", e.date)
                put("source", e.source)
                put("title", e.title)
                put("link", e.link)
                put("status", e.status.name)
                e.detail?.let { put("detail", it) }
                e.fileName?.let { put("fileName", it) }
                e.fileUri?.let { put("fileUri", it) }
            })
        }
    }.toString()

    private fun fromJson(text: String): List<HistoryEntry> {
        val array = JSONArray(text)
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            try {
                HistoryEntry(
                    id = o.getString("id"),
                    date = o.getLong("date"),
                    source = o.optString("source"),
                    title = o.optString("title"),
                    link = o.optString("link"),
                    status = HistoryStatus.valueOf(o.getString("status")),
                    detail = o.stringOrNull("detail"),
                    fileName = o.stringOrNull("fileName"),
                    fileUri = o.stringOrNull("fileUri"),
                )
            } catch (e: Exception) { null }
        }
    }

    private fun JSONObject.stringOrNull(key: String): String? = if (has(key)) getString(key) else null
}

// ---------------------------------------------------------------- Saved files

/** Opening, sharing and deleting saved files, whether in the default folder (file://) or a chosen one (content://). */
object SavedFiles {
    fun exists(context: Context, uri: String): Boolean = try {
        val u = Uri.parse(uri)
        if (u.scheme == "file") File(u.path!!).exists()
        else DocumentFile.fromSingleUri(context, u)?.exists() == true
    } catch (e: Exception) { false }

    fun delete(context: Context, uri: String) {
        try {
            val u = Uri.parse(uri)
            if (u.scheme == "file") File(u.path!!).delete()
            else DocumentFile.fromSingleUri(context, u)?.delete()
        } catch (e: Exception) {
        }
    }

    fun mimeType(name: String): String = when (val ext = name.substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "wav" -> "audio/wav"
        "mp4" -> "video/mp4"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    /** A content:// link other apps are allowed to read. */
    private fun shareable(context: Context, uri: String): Uri {
        val u = Uri.parse(uri)
        return if (u.scheme == "file") FileProvider.getUriForFile(context, "${context.packageName}.files", File(u.path!!)) else u
    }

    fun open(context: Context, uri: String, name: String) {
        start(context) {
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(shareable(context, uri), mimeType(name))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun share(context: Context, uri: String, name: String) {
        start(context) {
            val send = Intent(Intent.ACTION_SEND)
                .setType(mimeType(name))
                .putExtra(Intent.EXTRA_STREAM, shareable(context, uri))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            Intent.createChooser(send, null)
        }
    }

    private fun start(context: Context, intent: () -> Intent) {
        try {
            context.startActivity(intent())
        } catch (e: Exception) {
            Toast.makeText(context, tr(R.string.toast_no_file_app), Toast.LENGTH_LONG).show()
        }
    }
}

// ---------------------------------------------------------------- Screen

private enum class HistoryFilter(@param:StringRes val label: Int, val matches: (HistoryEntry) -> Boolean) {
    ALL(R.string.filter_all, { true }),
    DONE(R.string.filter_downloaded, { it.status == HistoryStatus.DONE }),
    FAILED(R.string.filter_failed, { it.status == HistoryStatus.FAILED || it.status == HistoryStatus.CANCELLED }),
    SKIPPED(R.string.filter_skipped, { it.status == HistoryStatus.SKIPPED }),
}

private enum class HistoryConfirm { CLEAR_ALL, DELETE_SELECTED_FILES, DELETE_ALL_DOWNLOADED }

/** Like desktop's History window: filter, retry, share, remove one, remove selected, or clear by type. */
@Composable
fun HistoryScreen(onDownloadAgain: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var filter by remember { mutableStateOf(HistoryFilter.ALL) }
    var selecting by remember { mutableStateOf(false) }
    val selection = remember { mutableStateListOf<String>() }
    var confirm by remember { mutableStateOf<HistoryConfirm?>(null) }
    var report by remember { mutableStateOf<ProblemReport?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    val shown = HistoryStore.entries.filter(filter.matches)

    fun finishSelecting() {
        selection.clear()
        selecting = false
    }

    FullScreenPage(
        title = stringResource(R.string.history_title),
        onClose = onClose,
        actions = {
            if (selecting) {
                TextButton(onClick = { finishSelecting() }) { Text(stringResource(R.string.cancel), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
            } else {
                Box {
                    TextButton(onClick = { menuOpen = true }, enabled = HistoryStore.entries.isNotEmpty()) {
                        Icon(painterResource(R.drawable.ic_more), contentDescription = null)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        MenuItem(stringResource(R.string.history_select)) { menuOpen = false; selecting = true }
                        HorizontalDivider()
                        MenuItem(stringResource(R.string.clear_downloaded)) {
                            menuOpen = false
                            HistoryStore.removeWhere { it.status == HistoryStatus.DONE }
                        }
                        MenuItem(stringResource(R.string.clear_failed)) {
                            menuOpen = false
                            HistoryStore.removeWhere { it.status == HistoryStatus.FAILED || it.status == HistoryStatus.CANCELLED }
                        }
                        MenuItem(stringResource(R.string.clear_skipped)) {
                            menuOpen = false
                            HistoryStore.removeWhere { it.status == HistoryStatus.SKIPPED }
                        }
                        MenuItem(stringResource(R.string.clear_all_menu), MaterialTheme.colorScheme.error) { menuOpen = false; confirm = HistoryConfirm.CLEAR_ALL }
                        HorizontalDivider()
                        MenuItem(stringResource(R.string.delete_all_files_menu), MaterialTheme.colorScheme.error) {
                            menuOpen = false
                            confirm = HistoryConfirm.DELETE_ALL_DOWNLOADED
                        }
                    }
                }
            }
        },
        bottomBar = {
            if (selecting) {
                HorizontalDivider(color = colors.outline)
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { selection.clear(); selection.addAll(shown.map { it.id }) }) {
                        Text(stringResource(R.string.select_all))
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { confirm = HistoryConfirm.DELETE_SELECTED_FILES },
                        enabled = HistoryStore.entries.any { it.id in selection && it.fileUri != null },
                    ) { Text(stringResource(R.string.delete_files)) }
                    TextButton(
                        onClick = { HistoryStore.remove(selection.toList()); finishSelecting() },
                        enabled = selection.isNotEmpty(),
                    ) {
                        Text(
                            stringResource(R.string.remove_count, selection.size),
                            color = if (selection.isNotEmpty()) MaterialTheme.colorScheme.error else colors.onSurfaceVariant,
                        )
                    }
                }
            }
        },
    ) {
        PillTabs(
            HistoryFilter.entries.map { stringResource(it.label) },
            filter.ordinal,
            { filter = HistoryFilter.entries[it] },
            Modifier.padding(vertical = 10.dp),
        )
        if (shown.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.nothing_here), fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(stringResource(R.string.history_empty), color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.id }) { entry ->
                    HistoryRow(
                        entry = entry,
                        selecting = selecting,
                        selected = entry.id in selection,
                        onToggle = { if (entry.id in selection) selection.remove(entry.id) else selection.add(entry.id) },
                        onDownloadAgain = { onDownloadAgain(entry.link) },
                        onReport = { report = ProblemReport("Site: ${entry.source}\nLink: ${entry.link}\nError: ${entry.detail ?: "-"}") },
                    )
                    HorizontalDivider(color = colors.outline)
                }
            }
        }
    }

    report?.let { ReportProblemDialog(it) { report = null } }
    confirm?.let { what ->
        val (button, note) = when (what) {
            HistoryConfirm.CLEAR_ALL ->
                stringResource(R.string.clear_all_history) to stringResource(R.string.clear_all_note)
            HistoryConfirm.DELETE_SELECTED_FILES ->
                stringResource(R.string.delete_count, selection.size) to stringResource(R.string.delete_selected_note)
            HistoryConfirm.DELETE_ALL_DOWNLOADED ->
                stringResource(R.string.delete_all_files) to stringResource(R.string.delete_all_note)
        }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.are_you_sure)) },
            text = { Text(note) },
            confirmButton = {
                TextButton(onClick = {
                    when (what) {
                        HistoryConfirm.CLEAR_ALL -> HistoryStore.removeWhere { true }
                        HistoryConfirm.DELETE_SELECTED_FILES -> {
                            HistoryStore.deleteFiles(context, selection.toList())
                            finishSelecting()
                        }
                        HistoryConfirm.DELETE_ALL_DOWNLOADED ->
                            HistoryStore.deleteFiles(context, HistoryStore.entries.filter { it.status == HistoryStatus.DONE }.map { it.id })
                    }
                    confirm = null
                }) { Text(button, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun HistoryRow(
    entry: HistoryEntry,
    selecting: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    onDownloadAgain: () -> Unit,
    onReport: () -> Unit,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    val fileExists = remember(entry.id) { entry.fileUri?.let { SavedFiles.exists(context, it) } == true }
    val (symbol, tint) = when (entry.status) {
        HistoryStatus.DONE -> "✓" to LocalPalette.current.success
        HistoryStatus.FAILED -> "✕" to MaterialTheme.colorScheme.error
        HistoryStatus.SKIPPED -> "↷" to colors.onSurfaceVariant
        HistoryStatus.CANCELLED -> "–" to colors.onSurfaceVariant
    }
    val end = when (entry.status) {
        HistoryStatus.DONE -> if (fileExists) entry.fileName ?: stringResource(R.string.saved) else stringResource(R.string.file_gone)
        HistoryStatus.SKIPPED -> entry.detail ?: stringResource(R.string.already_existed)
        HistoryStatus.FAILED -> entry.detail ?: stringResource(R.string.failed)
        HistoryStatus.CANCELLED -> stringResource(R.string.cancelled)
    }
    val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.date))

    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { if (selecting) onToggle() else menu = true }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting) Checkbox(checked = selected, onCheckedChange = { onToggle() })
            Text(symbol, color = tint, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.title, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(entry.source, date, end).joinToString(" · "),
                    fontSize = 12.sp,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            // Utility tab entries are edited files, with no link to download again
            if (entry.link.startsWith("http")) {
                MenuItem(stringResource(if (entry.status == HistoryStatus.DONE) R.string.download_again else R.string.retry)) {
                    menu = false
                    onDownloadAgain()
                }
                MenuItem(stringResource(R.string.copy_link)) {
                    menu = false
                    ClipboardWatcher.copy(context, entry.link)
                    Toast.makeText(context, tr(R.string.link_copied), Toast.LENGTH_SHORT).show()
                }
            }
            if (entry.status == HistoryStatus.FAILED) MenuItem(stringResource(R.string.report_problem)) { menu = false; onReport() }
            if (fileExists && entry.fileUri != null) {
                val name = entry.fileName ?: entry.title
                MenuItem(stringResource(R.string.open_file)) { menu = false; SavedFiles.open(context, entry.fileUri, name) }
                MenuItem(stringResource(R.string.share_file)) { menu = false; SavedFiles.share(context, entry.fileUri, name) }
            }
            HorizontalDivider()
            MenuItem(stringResource(R.string.remove_from_history), MaterialTheme.colorScheme.error) { menu = false; HistoryStore.remove(listOf(entry.id)) }
            if (fileExists) {
                MenuItem(stringResource(R.string.delete_file), MaterialTheme.colorScheme.error) { menu = false; HistoryStore.deleteFiles(context, listOf(entry.id)) }
            }
        }
    }
}

@Composable
private fun MenuItem(text: String, color: Color = Color.Unspecified, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text, color = color) }, onClick = onClick)
}
