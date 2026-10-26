package com.nil.mediadownloader

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The Utility tab's tools. [keepsFormat]: the result is the same file type, so it can replace the original. */
enum class Tool(@param:StringRes val label: Int, @param:StringRes val about: Int, val keepsFormat: Boolean) {
    NORMALIZE(R.string.tool_normalize, R.string.tool_normalize_about, true),
    CONVERT(R.string.tool_convert, R.string.tool_convert_about, false),
    COVER(R.string.tool_cover, R.string.tool_cover_about, true),
    TAGS(R.string.tool_tags, R.string.tool_tags_about, true),
    TRIM(R.string.tool_trim, R.string.tool_trim_about, true),
    SPEED(R.string.tool_speed, R.string.tool_speed_about, true),
    EFFECTS(R.string.tool_effects, R.string.tool_effects_about, true),
}

enum class CoverMode { ADD, REMOVE, SAVE }
enum class SpeedMode { SLOWED, NIGHTCORE, SPEED, PITCH }

private val LUFS_OPTIONS = listOf(-14 to R.string.lufs_14, -16 to R.string.lufs_16, -11 to R.string.lufs_11, -23 to R.string.lufs_23)
// format to label; the two video ones get "(video)" added when shown
private val CONVERT_FORMATS = listOf(
    "mp3" to "MP3", "m4a" to "M4A (AAC)", "flac" to "FLAC", "wav" to "WAV", "ogg" to "OGG (Vorbis)",
    "opus" to "Opus", "alac" to "ALAC (M4A)", "mp4" to "MP4", "mkv" to "MKV",
)
private val FADE_OPTIONS = listOf(0, 1, 2, 3, 5, 8)
private val SPEED_OPTIONS = listOf(0.5, 0.75, 0.9, 1.1, 1.25, 1.5, 2.0)
private val SEMITONE_OPTIONS = listOf(-6, -5, -4, -3, -2, -1, 1, 2, 3, 4, 5, 6)
private val VOLUME_OPTIONS = listOf(-6, -3, 0, 3, 6)

/** Runs the Utility tab's tools and holds what it shows. Choices live here, so switching tabs keeps them. */
class UtilityState {
    val task = TaskState(R.string.tab_utility)
    var tool by mutableStateOf(Tool.NORMALIZE)
    val files = mutableStateListOf<PickedFile>()
    var replaceOriginal by mutableStateOf(false)

    var targetLufs by mutableIntStateOf(-14)
    var convertTo by mutableStateOf("mp3")
    var convertKbps by mutableIntStateOf(320)
    var coverMode by mutableStateOf(CoverMode.ADD)
    var coverImage by mutableStateOf<Uri?>(null)
    var cropSquare by mutableStateOf(true)
    val tags = mutableStateMapOf<String, String>()
    var tagsFromName by mutableStateOf(false)
    var trimStart by mutableStateOf("")
    var trimEnd by mutableStateOf("")
    var fadeIn by mutableIntStateOf(0)
    var fadeOut by mutableIntStateOf(0)
    var speedMode by mutableStateOf(SpeedMode.SLOWED)
    var speed by mutableStateOf(1.25)
    var semitones by mutableIntStateOf(2)
    var bassBoost by mutableStateOf(false)
    var volumeDb by mutableIntStateOf(0)
    var trimSilence by mutableStateOf(false)

    /** The file whose tags are shown in the Tag editor, so they're only read once. */
    private var tagsReadFrom: Uri? = null

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences("utility_tab", Context.MODE_PRIVATE)

    fun cancel() {
        Downloader.cancel(task)
        if (task.running) task.status = tr(R.string.status_cancelling)
    }

    /** Start: checks the choices, then runs the tool on every file, one at a time. */
    fun start(context: Context) {
        if (task.running) return
        if (files.isEmpty()) { task.status = tr(R.string.util_add_files_first); return }
        if (tool == Tool.COVER && coverMode == CoverMode.ADD && coverImage == null) { task.status = tr(R.string.util_pick_picture); return }
        if (tool == Tool.EFFECTS && !bassBoost && volumeDb == 0 && !trimSilence) { task.status = tr(R.string.util_pick_effect); return }
        val start = if (trimStart.isBlank()) 0.0 else parseTime(trimStart)
        val end = if (trimEnd.isBlank()) null else parseTime(trimEnd)
        if (tool == Tool.TRIM && (start == null || (trimEnd.isNotBlank() && end == null))) { task.status = tr(R.string.util_bad_time); return }
        if (tool == Tool.TRIM && end != null && end <= (start ?: 0.0)) { task.status = tr(R.string.err_trim_range); return }

        val job = ToolJob(
            tool = tool, files = files.toList(),
            replaceOriginal = replaceOriginal && tool.keepsFormat && !(tool == Tool.COVER && coverMode == CoverMode.SAVE),
            targetLufs = targetLufs, convertTo = convertTo, convertKbps = convertKbps,
            coverMode = coverMode, coverImage = coverImage, cropSquare = cropSquare,
            tags = tags.toMap(), tagsFromName = tagsFromName,
            trimStart = start ?: 0.0, trimEnd = end, fadeIn = fadeIn, fadeOut = fadeOut,
            speedMode = speedMode, speed = speed, semitones = semitones,
            bassBoost = bassBoost, volumeDb = volumeDb, trimSilence = trimSilence,
        )
        val app = context.applicationContext
        startJob(app, task)
        AppState.scope.launch {
            run(app, job)
            endJob(app, task)
        }
    }

    private suspend fun run(context: Context, job: ToolJob) {
        task.status = tr(R.string.status_preparing)
        try {
            withContext(Dispatchers.IO) { Downloader.init(context) } // unpacks FFmpeg on first use
        } catch (e: Exception) {
            task.addLog(tr(R.string.log_startup_failed, e.message ?: ""))
            task.status = tr(R.string.status_cant_start)
            return
        }
        val folder = loadSavedFolder(context, prefs(context).getString("folder", null))
        val workRoot = File(context.cacheDir, "work-utility").apply { deleteRecursively(); mkdirs() }
        val source = tr(job.tool.label)

        var cover: File? = null
        if (job.tool == Tool.COVER && job.coverMode == CoverMode.ADD) {
            task.status = tr(R.string.util_preparing_cover)
            try {
                cover = withContext(Dispatchers.IO) {
                    val picture = PickedFiles.copyIn(context, job.coverImage!!, workRoot, "picture")
                    File(workRoot, "cover.jpg").also { Tools.prepareCover(context, task, picture, it, job.cropSquare) }
                }
            } catch (e: Exception) {
                if (!task.cancelled) task.addLog(tr(R.string.err_cover_image, e.message ?: ""))
                task.status = tr(R.string.err_cover_image, e.message ?: "")
                workRoot.deleteRecursively()
                return
            }
        }

        var done = 0
        var skipped = 0
        var failed = 0
        val count = job.files.size
        for ((i, picked) in job.files.withIndex()) {
            if (task.cancelled) break
            val tag = "[${i + 1}/$count]"
            task.status = tr(R.string.util_working_on, tag, picked.name)
            task.progress = i.toFloat() / count
            val dir = File(workRoot, "$i")
            try {
                val outcome = withContext(Dispatchers.IO) {
                    val input = PickedFiles.copyIn(context, picked.uri, dir, "input" + if (picked.ext.isNotEmpty()) ".${picked.ext}" else "")
                    Tools.process(context, task, job, picked, input, File(dir, "out").apply { mkdirs() }, cover) { f ->
                        AppState.scope.launch { task.progress = (i + f) / count }
                    }
                }
                when (outcome) {
                    is ToolOutcome.Skipped -> {
                        task.addLog(tr(R.string.log_skipped_song, tag, outcome.reason))
                        skipped++
                    }
                    is ToolOutcome.Made -> {
                        var replaced = false
                        if (job.replaceOriginal && outcome.sameFormat) {
                            try {
                                withContext(Dispatchers.IO) { PickedFiles.writeBack(context, picked.uri, outcome.file) }
                                replaced = true
                            } catch (e: Exception) {
                                task.addLog(tr(R.string.util_replace_failed, tag, e.message ?: ""))
                            }
                        }
                        val uri = if (replaced) picked.uri.toString()
                        else withContext(Dispatchers.IO) { Downloader.save(context, outcome.file, folder) }
                        val name = if (replaced) picked.name else outcome.file.name
                        task.addLog(tr(if (replaced) R.string.util_replaced else R.string.log_done, tag, name))
                        HistoryStore.add(source, name.substringBeforeLast('.'), "", HistoryStatus.DONE, fileName = name, fileUri = uri)
                        done++
                    }
                }
            } catch (e: YoutubeDL.CanceledException) {
                task.addLog(tr(R.string.log_cancelled, tag))
            } catch (e: Exception) {
                if (task.cancelled) {
                    task.addLog(tr(R.string.log_cancelled, tag))
                } else {
                    task.addLog(tr(R.string.log_failed, tag, e.message ?: e.javaClass.simpleName))
                    HistoryStore.add(source, picked.base, "", HistoryStatus.FAILED, detail = e.message)
                    failed++
                }
            } finally {
                dir.deleteRecursively()
            }
        }

        workRoot.deleteRecursively()
        task.progress = 0f
        task.status = if (task.cancelled) tr(R.string.util_cancelled, done) else tr(R.string.util_finished, done, skipped, failed)
    }

    /** Tag editor with one file picked: shows its current tags to edit. */
    suspend fun loadTags(context: Context) {
        val only = files.singleOrNull()
        if (only == null) {
            if (tagsReadFrom != null) { tags.clear(); tagsReadFrom = null }
            return
        }
        if (only.uri == tagsReadFrom || task.running) return
        tagsReadFrom = only.uri
        val app = context.applicationContext
        try {
            val read = withContext(Dispatchers.IO) {
                Downloader.init(app)
                val dir = File(app.cacheDir, "probe").apply { deleteRecursively() }
                try {
                    Tools.readTags(app, PickedFiles.copyIn(app, only.uri, dir, "input" + if (only.ext.isNotEmpty()) ".${only.ext}" else ""))
                } finally {
                    dir.deleteRecursively()
                }
            }
            tags.clear()
            tags.putAll(read)
        } catch (e: Exception) {
            task.addLog(tr(R.string.log_failed, only.name, e.message ?: ""))
        }
    }
}

/** "1:23", "83" or "1:02:03" to seconds; null if it isn't a time. */
private fun parseTime(text: String): Double? {
    val parts = text.trim().replace(',', '.').split(':')
    if (parts.size > 3) return null
    var seconds = 0.0
    for (p in parts) seconds = seconds * 60 + (p.toDoubleOrNull()?.takeIf { it >= 0 } ?: return null)
    return seconds
}

// ---------------------------------------------------------------- Screen

@Composable
fun UtilityTab(state: UtilityState, scope: CoroutineScope, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val task = state.task
    val prefs = remember { state.prefs(context) }
    var folderUri by remember { mutableStateOf(loadSavedFolder(context, prefs.getString("folder", null))) }
    val idle = !task.running

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.filter { uri -> state.files.none { it.uri == uri } }.forEach { state.files += PickedFile(it, PickedFiles.name(context, it)) }
    }
    val picturePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) state.coverImage = uri
    }
    LaunchedEffect(state.tool, state.files.toList()) {
        if (state.tool == Tool.TAGS) state.loadTags(context)
    }

    Column(
        modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        SectionLabel(stringResource(R.string.util_tool))
        ToolChips(state.tool, idle) { state.tool = it }
        Text(stringResource(state.tool.about), color = colors.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))

        // Files
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(stringResource(R.string.util_files), Modifier.weight(1f))
            SmallLink(stringResource(R.string.util_add_files), enabled = idle) {
                filePicker.launch(
                    if (state.tool == Tool.CONVERT) arrayOf("audio/*", "video/*", "application/ogg")
                    else arrayOf("audio/*", "application/ogg")
                )
            }
            SmallLink(stringResource(R.string.clear), enabled = idle && state.files.isNotEmpty()) { state.files.clear() }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.surfaceVariant, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            if (state.files.isEmpty()) {
                Text(stringResource(R.string.util_no_files), color = colors.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
            }
            state.files.toList().forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(f.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    TextButton(onClick = { state.files.remove(f) }, enabled = idle) { Icon(painterResource(R.drawable.ic_close), contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp)) }
                }
            }
        }

        // The chosen tool's options
        when (state.tool) {
            Tool.NORMALIZE -> NormalizeOptions(state, idle)
            Tool.CONVERT -> ConvertOptions(state, idle)
            Tool.COVER -> CoverOptions(state, idle) {
                picturePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            Tool.TAGS -> TagOptions(state, idle)
            Tool.TRIM -> TrimOptions(state, idle)
            Tool.SPEED -> SpeedOptions(state, idle)
            Tool.EFFECTS -> EffectOptions(state, idle)
        }

        SaveFolderRow(folderUri, enabled = idle) { uri ->
            folderUri = uri
            prefs.edit().apply { if (uri == null) remove("folder") else putString("folder", uri.toString()) }.apply()
        }
        if (state.tool.keepsFormat && !(state.tool == Tool.COVER && state.coverMode == CoverMode.SAVE)) {
            CheckRow(stringResource(R.string.replace_original), state.replaceOriginal, idle) { state.replaceOriginal = it }
            Hint(stringResource(R.string.replace_hint))
        }

        ActionButtons(
            task, folderUri,
            onDownload = { state.start(context) }, onCancel = { state.cancel() },
            idleText = R.string.util_start, busyText = R.string.util_working,
        )
        ProgressAndLog(task, scope, showUpdate = false)
    }
}

/** Row of tool buttons, scrolling sideways. */
@Composable
private fun ToolChips(selected: Tool, enabled: Boolean, onSelect: (Tool) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Tool.entries.forEach { t ->
            val on = t == selected
            Text(
                stringResource(t.label),
                color = if (on) colors.onPrimary else colors.onSurface,
                fontSize = 13.sp,
                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .clip(PillShape)
                    .background(if (on) colors.primary else colors.surfaceContainer)
                    .clickable(enabled = enabled) { onSelect(t) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun OptionRow(label: String, content: @Composable () -> Unit) {
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(96.dp))
        content()
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
}

@Composable
private fun NormalizeOptions(state: UtilityState, enabled: Boolean) {
    SectionLabel(stringResource(R.string.util_options))
    OptionRow(stringResource(R.string.util_target)) {
        Picker(LUFS_OPTIONS.indexOfFirst { it.first == state.targetLufs }, LUFS_OPTIONS.map { stringResource(it.second) }, enabled) {
            state.targetLufs = LUFS_OPTIONS[it].first
        }
    }
}

@Composable
private fun ConvertOptions(state: UtilityState, enabled: Boolean) {
    SectionLabel(stringResource(R.string.util_options))
    OptionRow(stringResource(R.string.util_convert_to)) {
        Picker(
            CONVERT_FORMATS.indexOfFirst { it.first == state.convertTo },
            CONVERT_FORMATS.map { (fmt, label) -> if (fmt == "mp4" || fmt == "mkv") stringResource(R.string.util_video_format, label) else label },
            enabled,
        ) { state.convertTo = CONVERT_FORMATS[it].first }
    }
    if (state.convertTo == "mp3") {
        OptionRow(stringResource(R.string.label_quality)) {
            val kbps = MP3_QUALITIES.map { it.second.removeSuffix("K").toInt() }
            Picker(kbps.indexOf(state.convertKbps), MP3_QUALITIES.map { it.first }, enabled) { state.convertKbps = kbps[it] }
        }
    }
    Hint(stringResource(R.string.util_convert_hint))
}

@Composable
private fun CoverOptions(state: UtilityState, enabled: Boolean, onChoosePicture: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    SectionLabel(stringResource(R.string.util_options))
    RadioRow(stringResource(R.string.cover_add), state.coverMode == CoverMode.ADD, enabled) { state.coverMode = CoverMode.ADD }
    RadioRow(stringResource(R.string.cover_remove), state.coverMode == CoverMode.REMOVE, enabled) { state.coverMode = CoverMode.REMOVE }
    RadioRow(stringResource(R.string.cover_save), state.coverMode == CoverMode.SAVE, enabled) { state.coverMode = CoverMode.SAVE }
    if (state.coverMode == CoverMode.ADD) {
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton(stringResource(R.string.cover_choose), enabled = enabled, onClick = onChoosePicture)
            state.coverImage?.let { uri ->
                AsyncImage(
                    model = uri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.padding(start = 12.dp).size(56.dp).clip(RoundedCornerShape(6.dp)).background(colors.surfaceVariant),
                )
            }
        }
        CheckRow(stringResource(R.string.cover_crop), state.cropSquare, enabled) { state.cropSquare = it }
    }
    Hint(stringResource(R.string.cover_hint))
}

@Composable
private fun TagOptions(state: UtilityState, enabled: Boolean) {
    val labels = listOf(
        R.string.tag_title, R.string.tag_artist, R.string.tag_album, R.string.tag_album_artist,
        R.string.tag_date, R.string.tag_genre, R.string.tag_track,
    )
    SectionLabel(stringResource(R.string.util_options))
    Hint(stringResource(if (state.files.size > 1) R.string.tags_hint_many else R.string.tags_hint_one))
    Tools.TAG_KEYS.zip(labels).forEach { (key, label) ->
        OutlinedTextField(
            value = state.tags[key].orEmpty(),
            onValueChange = { state.tags[key] = it },
            label = { Text(stringResource(label)) },
            singleLine = true,
            enabled = enabled && !(state.tagsFromName && (key == "title" || key == "artist")),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
    CheckRow(stringResource(R.string.tags_from_name), state.tagsFromName, enabled) { state.tagsFromName = it }
}

@Composable
private fun TrimOptions(state: UtilityState, enabled: Boolean) {
    val off = stringResource(R.string.util_off)
    val fadeLabels = FADE_OPTIONS.map { if (it == 0) off else stringResource(R.string.seconds_short, it) }
    SectionLabel(stringResource(R.string.util_options))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.trimStart, onValueChange = { state.trimStart = it },
            label = { Text(stringResource(R.string.trim_start)) }, placeholder = { Text("0:00") },
            singleLine = true, enabled = enabled, modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = state.trimEnd, onValueChange = { state.trimEnd = it },
            label = { Text(stringResource(R.string.trim_end)) }, placeholder = { Text("3:30") },
            singleLine = true, enabled = enabled, modifier = Modifier.weight(1f),
        )
    }
    OptionRow(stringResource(R.string.fade_in)) {
        Picker(FADE_OPTIONS.indexOf(state.fadeIn), fadeLabels, enabled) { state.fadeIn = FADE_OPTIONS[it] }
    }
    OptionRow(stringResource(R.string.fade_out)) {
        Picker(FADE_OPTIONS.indexOf(state.fadeOut), fadeLabels, enabled) { state.fadeOut = FADE_OPTIONS[it] }
    }
    Hint(stringResource(R.string.trim_hint))
}

@Composable
private fun SpeedOptions(state: UtilityState, enabled: Boolean) {
    SectionLabel(stringResource(R.string.util_options))
    RadioRow(stringResource(R.string.speed_slowed), state.speedMode == SpeedMode.SLOWED, enabled) { state.speedMode = SpeedMode.SLOWED }
    RadioRow(stringResource(R.string.speed_nightcore), state.speedMode == SpeedMode.NIGHTCORE, enabled) { state.speedMode = SpeedMode.NIGHTCORE }
    RadioRow(stringResource(R.string.speed_custom), state.speedMode == SpeedMode.SPEED, enabled) { state.speedMode = SpeedMode.SPEED }
    RadioRow(stringResource(R.string.speed_pitch), state.speedMode == SpeedMode.PITCH, enabled) { state.speedMode = SpeedMode.PITCH }
    when (state.speedMode) {
        SpeedMode.SPEED -> OptionRow(stringResource(R.string.speed_label)) {
            Picker(SPEED_OPTIONS.indexOf(state.speed), SPEED_OPTIONS.map { "${it.toString().removeSuffix(".0")}×" }, enabled) {
                state.speed = SPEED_OPTIONS[it]
            }
        }
        SpeedMode.PITCH -> OptionRow(stringResource(R.string.pitch_label)) {
            Picker(
                SEMITONE_OPTIONS.indexOf(state.semitones),
                SEMITONE_OPTIONS.map { stringResource(R.string.semitones, if (it > 0) "+$it" else "$it") },
                enabled,
            ) { state.semitones = SEMITONE_OPTIONS[it] }
        }
        else -> {}
    }
}

@Composable
private fun EffectOptions(state: UtilityState, enabled: Boolean) {
    val same = stringResource(R.string.volume_same)
    SectionLabel(stringResource(R.string.util_options))
    CheckRow(stringResource(R.string.effect_bass), state.bassBoost, enabled) { state.bassBoost = it }
    OptionRow(stringResource(R.string.effect_volume)) {
        Picker(
            VOLUME_OPTIONS.indexOf(state.volumeDb),
            VOLUME_OPTIONS.map { if (it == 0) same else if (it > 0) "+$it dB" else "$it dB" },
            enabled,
        ) { state.volumeDb = VOLUME_OPTIONS[it] }
    }
    CheckRow(stringResource(R.string.effect_silence), state.trimSilence, enabled) { state.trimSilence = it }
}
