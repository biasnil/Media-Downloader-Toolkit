package com.nil.mediadownloader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Mobile version of desktop's gear-icon settings window. Changes save instantly. */
@Composable
fun SettingsDialog(onClose: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = colors.background,
            modifier = Modifier.fillMaxWidth(0.95f),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Settings", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    Hint("Applies to the YouTube and Other Sites tabs.")

                    SectionLabel("Thumbnail")
                    CheckRow("Embed thumbnail as cover art", AppSettings.embedThumbnail, true) {
                        AppSettings.updateEmbedThumbnail(it)
                    }

                    SectionLabel("Subtitles")
                    CheckRow("Download subtitles (video downloads only)", AppSettings.downloadSubtitles, true) {
                        AppSettings.updateDownloadSubtitles(it)
                    }
                    OutlinedTextField(
                        value = AppSettings.subtitleLangs,
                        onValueChange = { AppSettings.updateSubtitleLangs(it) },
                        label = { Text("Languages") },
                        singleLine = true,
                        enabled = AppSettings.downloadSubtitles,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Hint("Comma-separated language codes, e.g. en,ms,zh-Hans. Built into the MP4 — turn them on in your video player.")

                    SectionLabel("Background downloads")
                    CheckRow("Keep downloading in the background", AppSettings.backgroundDownloads, !Downloader.busy) {
                        AppSettings.updateBackgroundDownloads(it)
                    }
                    Hint(
                        "Shows a progress notification with a Cancel button, and downloads keep going " +
                            "with the screen off or in other apps. When off, the screen stays on instead."
                    )

                    SectionLabel("Theme")
                    val mode = AppSettings.darkMode
                    RadioRow("Follow phone setting", mode == null, true) { AppSettings.updateDarkMode(null) }
                    RadioRow("Light", mode == false, true) { AppSettings.updateDarkMode(false) }
                    RadioRow("Dark", mode == true, true) { AppSettings.updateDarkMode(true) }
                }
                PrimaryButton("Close", Modifier.fillMaxWidth().padding(top = 12.dp), onClick = onClose)
            }
        }
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
