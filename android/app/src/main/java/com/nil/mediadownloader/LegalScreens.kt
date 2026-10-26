package com.nil.mediadownloader

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date

/** Terms & Conditions must be agreed to again every 10 days. */
object Terms {
    private const val VALID_DAYS = 10
    private const val DAY_MS = 24L * 60 * 60 * 1000

    private fun prefs(context: Context) = context.getSharedPreferences("legal", Context.MODE_PRIVATE)

    fun agreedAt(context: Context): Long? = prefs(context).getLong("terms_agreed_at", 0L).takeIf { it > 0 }

    fun expiresAt(context: Context): Long? = agreedAt(context)?.plus(VALID_DAYS * DAY_MS)

    fun needsAgreement(context: Context): Boolean {
        val expires = expiresAt(context) ?: return true
        return System.currentTimeMillis() >= expires
    }

    fun agree(context: Context) {
        prefs(context).edit().putLong("terms_agreed_at", System.currentTimeMillis()).apply()
    }
}

/** (heading, text) pairs, in order. */
private val TERMS_SECTIONS = listOf(
    R.string.terms_1_title to R.string.terms_1_text,
    R.string.terms_2_title to R.string.terms_2_text,
    R.string.terms_3_title to R.string.terms_3_text,
    R.string.terms_4_title to R.string.terms_4_text,
    R.string.terms_5_title to R.string.terms_5_text,
)

/**
 * Shown full screen when agreement is needed ([onAgree] set, can't be closed),
 * or read from Settings → About ([onAgree] null).
 */
@Composable
fun TermsScreen(onAgree: (() -> Unit)?, onClose: () -> Unit = {}) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    FullScreenPage(
        title = stringResource(R.string.terms_title),
        onClose = if (onAgree == null) onClose else null,
        bottomBar = {
            if (onAgree != null) PrimaryButton(stringResource(R.string.i_agree), Modifier.fillMaxWidth().padding(16.dp), onClick = onAgree)
        },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text(stringResource(R.string.terms_intro), color = colors.onSurfaceVariant)
            TERMS_SECTIONS.forEachIndexed { i, (heading, text) ->
                Text(
                    "${i + 1}. ${stringResource(heading)}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
                )
                Text(stringResource(text))
            }
            val agreed = Terms.agreedAt(context)
            val next = Terms.expiresAt(context)
            if (onAgree == null && agreed != null && next != null) {
                val format = DateFormat.getDateInstance(DateFormat.LONG)
                Text(
                    stringResource(R.string.terms_agreed, format.format(Date(agreed)), format.format(Date(next))),
                    color = colors.onSurfaceVariant,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 18.dp),
                )
            }
        }
    }
}

@Composable
fun PrivacyScreen(onClose: () -> Unit) {
    FullScreenPage(title = stringResource(R.string.privacy_title), onClose = onClose) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text("🛡︎  " + stringResource(R.string.privacy_summary))
            PageHeading(stringResource(R.string.privacy_stored_title))
            listOf(R.string.privacy_stored_files, R.string.privacy_stored_history, R.string.privacy_stored_settings).forEach {
                Text("•  " + stringResource(it), Modifier.padding(vertical = 2.dp))
            }
            PageHeading(stringResource(R.string.privacy_online_title))
            Text(stringResource(R.string.privacy_online))
            PageHeading(stringResource(R.string.privacy_delete_title))
            Text(stringResource(R.string.privacy_delete))
        }
    }
}

/** An open-source project the app uses. */
private class Project(val name: String, val about: Int, val license: String, val url: String)

private val PROJECTS = listOf(
    Project("yt-dlp", R.string.ack_ytdlp, "The Unlicense", "https://github.com/yt-dlp/yt-dlp"),
    Project("youtubedl-android", R.string.ack_youtubedl, "GNU GPL 3.0", "https://github.com/JunkFood02/youtubedl-android"),
    Project("FFmpeg", R.string.ack_ffmpeg, "GNU LGPL 2.1 / GPL", "https://ffmpeg.org"),
    Project("Python", R.string.ack_python, "PSF License", "https://www.python.org"),
    Project("Coil", R.string.ack_coil, "Apache License 2.0", "https://github.com/coil-kt/coil"),
)

@Composable
fun AcknowledgementsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    FullScreenPage(title = stringResource(R.string.ack_title), onClose = onClose) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text(stringResource(R.string.ack_thanks))
            PROJECTS.forEach { p ->
                PageHeading(p.name)
                Text(stringResource(p.about))
                Text(p.license, color = colors.onSurfaceVariant, fontSize = 13.sp)
                TextButton(onClick = { Reports.openLink(context, p.url) }, contentPadding = PaddingValues(0.dp)) {
                    Text(p.url, color = colors.primary, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun PageHeading(text: String) {
    Text(text, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
}
