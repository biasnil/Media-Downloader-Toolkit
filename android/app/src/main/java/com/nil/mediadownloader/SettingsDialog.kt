package com.nil.mediadownloader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** KB/s; 0 = unlimited */
private val SPEED_OPTIONS = listOf(0, 250, 500, 1024, 2048, 5120)
private val CONFIDENCE_OPTIONS = listOf(50, 60, 70, 80, 90)

private fun speedLabel(kb: Int) = if (kb >= 1024) "${kb / 1024} MB/s" else "$kb KB/s"

private enum class AboutPage { TERMS, PRIVACY, ACKNOWLEDGEMENTS }

/** Mobile version of desktop's gear-icon settings window. Changes save instantly. */
@Composable
fun SettingsDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var page by remember { mutableStateOf<AboutPage?>(null) }
    var report by remember { mutableStateOf<ProblemReport?>(null) }
    var checking by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<UpdateChecker.Result?>(null) }
    val scope = rememberCoroutineScope()

    update?.let { UpdateDialog(it) { update = null } }
    when (page) {
        AboutPage.TERMS -> TermsScreen(onAgree = null, onClose = { page = null })
        AboutPage.PRIVACY -> PrivacyScreen(onClose = { page = null })
        AboutPage.ACKNOWLEDGEMENTS -> AcknowledgementsScreen(onClose = { page = null })
        null -> {}
    }
    report?.let { ReportProblemDialog(it) { report = null } }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = colors.background,
            modifier = Modifier.fillMaxWidth(0.95f),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.settings_title), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    Hint(stringResource(R.string.settings_hint))

                    SectionLabel(stringResource(R.string.thumbnail))
                    CheckRow(stringResource(R.string.embed_thumbnail), AppSettings.embedThumbnail, true) {
                        AppSettings.updateEmbedThumbnail(it)
                    }

                    SectionLabel(stringResource(R.string.subtitles))
                    CheckRow(stringResource(R.string.download_subtitles), AppSettings.downloadSubtitles, true) {
                        AppSettings.updateDownloadSubtitles(it)
                    }
                    OutlinedTextField(
                        value = AppSettings.subtitleLangs,
                        onValueChange = { AppSettings.updateSubtitleLangs(it) },
                        label = { Text(stringResource(R.string.subtitle_languages)) },
                        singleLine = true,
                        enabled = AppSettings.downloadSubtitles,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Hint(stringResource(R.string.subtitles_hint))

                    InfoHeader(stringResource(R.string.clipboard), stringResource(R.string.clipboard_info))
                    CheckRow(stringResource(R.string.detect_links), AppSettings.clipboardDetect, true) {
                        AppSettings.updateClipboardDetect(it)
                    }

                    InfoHeader(stringResource(R.string.downloads), stringResource(R.string.downloads_info))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.at_once), Modifier.width(112.dp))
                        Picker(AppSettings.maxConcurrent - 1, (1..5).map { "$it" }, true) {
                            AppSettings.updateMaxConcurrent(it + 1)
                        }
                    }
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.speed_limit), Modifier.width(112.dp))
                        val unlimited = stringResource(R.string.unlimited)
                        Picker(
                            SPEED_OPTIONS.indexOf(AppSettings.speedLimitKB),
                            SPEED_OPTIONS.map { if (it == 0) unlimited else speedLabel(it) },
                            true,
                        ) { AppSettings.updateSpeedLimitKB(SPEED_OPTIONS[it]) }
                    }
                    Hint(stringResource(R.string.next_download_hint))

                    InfoHeader(stringResource(R.string.background_title), stringResource(R.string.background_info))
                    CheckRow(stringResource(R.string.keep_background), AppSettings.backgroundDownloads, !AppState.anyRunning) {
                        AppSettings.updateBackgroundDownloads(it)
                    }
                    CheckRow(stringResource(R.string.notify_done), AppSettings.notifyWhenDone, true) {
                        AppSettings.updateNotifyWhenDone(it)
                    }

                    InfoHeader(stringResource(R.string.tab_spotify), stringResource(R.string.spotify_info))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.confidence), Modifier.width(112.dp))
                        Picker(
                            CONFIDENCE_OPTIONS.indexOf(AppSettings.spotifyConfidence),
                            CONFIDENCE_OPTIONS.map { "$it%" },
                            true,
                        ) { AppSettings.updateSpotifyConfidence(CONFIDENCE_OPTIONS[it]) }
                    }
                    OutlinedTextField(
                        value = AppSettings.spotifySearchTags,
                        onValueChange = { AppSettings.updateSpotifySearchTags(it) },
                        label = { Text(stringResource(R.string.search_tags)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                    Hint(stringResource(R.string.search_tags_hint, AppSettings.DEFAULT_SPOTIFY_TAGS))

                    SectionLabel(stringResource(R.string.language))
                    // Each language written in its own language, so it's findable whatever the app shows now
                    val codes = listOf("") + LANGUAGES.map { it.first }
                    val current = Lang.current(context)
                    Picker(
                        codes.indexOf(current).coerceAtLeast(0),
                        listOf(stringResource(R.string.follow_phone)) + LANGUAGES.map { it.second },
                        true,
                    ) { i -> if (codes[i] != current) context.findActivity()?.let { Lang.set(it, codes[i]) } }

                    SectionLabel(stringResource(R.string.theme))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ThemeChoice.entries.forEach { choice ->
                            ThemeSwatch(choice, selected = AppSettings.theme == choice) { AppSettings.updateTheme(choice) }
                        }
                    }
                    RadioRow(stringResource(R.string.follow_phone_setting), AppSettings.theme == null, true) { AppSettings.updateTheme(null) }

                    SectionLabel(stringResource(R.string.about))
                    AboutLink(stringResource(R.string.report_problem)) { report = ProblemReport() }
                    AboutLink(stringResource(R.string.source_code)) { Reports.openLink(context, Reports.REPOSITORY) }
                    AboutLink(stringResource(R.string.terms_title)) { page = AboutPage.TERMS }
                    AboutLink(stringResource(R.string.privacy_title)) { page = AboutPage.PRIVACY }
                    AboutLink(stringResource(R.string.ack_title)) { page = AboutPage.ACKNOWLEDGEMENTS }
                    // Checks GitHub only when tapped, never on its own
                    AboutLink(
                        if (checking) stringResource(R.string.update_checking)
                        else stringResource(R.string.update_tap, UpdateChecker.currentVersion(context)),
                    ) {
                        if (!checking) {
                            checking = true
                            scope.launch {
                                update = withContext(Dispatchers.IO) { UpdateChecker.check(context) }
                                checking = false
                            }
                        }
                    }
                }
                PrimaryButton(stringResource(R.string.close), Modifier.fillMaxWidth().padding(top = 12.dp), onClick = onClose)
            }
        }
    }
}

/** The result of tapping Version. */
@Composable
private fun UpdateDialog(result: UpdateChecker.Result, onClose: () -> Unit) {
    val context = LocalContext.current
    val newer = result as? UpdateChecker.Result.Newer
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(if (newer != null) R.string.update_available_title else R.string.update_title)) },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                Text(
                    when (result) {
                        is UpdateChecker.Result.Newer ->
                            stringResource(R.string.update_available, result.version, UpdateChecker.currentVersion(context))
                        is UpdateChecker.Result.UpToDate -> stringResource(R.string.update_latest, result.version)
                        UpdateChecker.Result.NoRelease -> stringResource(R.string.update_none)
                        UpdateChecker.Result.Busy -> stringResource(R.string.update_busy)
                        is UpdateChecker.Result.Failed -> stringResource(R.string.update_failed, result.reason)
                    }
                )
                if (newer != null && newer.notes.isNotBlank()) {
                    Text(stringResource(R.string.update_notes), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    Text(newer.notes, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            if (newer != null) {
                TextButton(onClick = { Reports.openLink(context, newer.url); onClose() }) { Text(stringResource(R.string.download)) }
            } else {
                TextButton(onClick = onClose) { Text(stringResource(R.string.ok)) }
            }
        },
        dismissButton = {
            if (newer != null) TextButton(onClick = onClose) { Text(stringResource(R.string.update_later)) }
        },
    )
}

/** A theme's background with its accent dot, and its name underneath. */
@Composable
private fun ThemeSwatch(choice: ThemeChoice, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val p = choice.palette
    Column(
        Modifier
            .width(64.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(56.dp)
                .border(if (selected) 3.dp else 1.dp, if (selected) colors.primary else colors.outline, RoundedCornerShape(16.dp))
                .padding(4.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(p.background),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(22.dp).background(p.accent, CircleShape))
        }
        Text(
            stringResource(choice.label),
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun AboutLink(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
        Text(text, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 4.dp),
    )
}
