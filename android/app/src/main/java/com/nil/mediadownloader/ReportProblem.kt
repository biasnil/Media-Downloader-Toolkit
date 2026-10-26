package com.nil.mediadownloader

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import java.util.Locale

/**
 * A problem report. Nothing is sent by the app itself: it opens an email app or GitHub with the
 * text filled in, and the user sees all of it before tapping Send / Submit.
 * [details] is what went wrong, e.g. the link and its error. Empty for a general report.
 */
data class ProblemReport(val details: String = "")

object Reports {
    const val EMAIL = "workacckwanrong@gmail.com"
    const val REPOSITORY = "https://github.com/biasnil/Media-Downloader-Toolkit"
    private const val SUBJECT = "Media Downloader (Android): problem report"

    /** "1.0 (1)" */
    fun appVersion(context: Context): String = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    } catch (e: Exception) { "?" }

    private fun body(context: Context, report: ProblemReport): String {
        val lines = mutableListOf(tr(R.string.report_prompt), "", "", "---")
        if (report.details.isNotEmpty()) lines += listOf(report.details, "")
        lines += listOf(
            "App: ${appVersion(context)}",
            "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "Device: ${Build.MANUFACTURER} ${Build.MODEL}",
            "Language: ${Locale.getDefault().toLanguageTag()}",
        )
        return lines.joinToString("\n")
    }

    fun sendEmail(context: Context, report: ProblemReport) {
        val body = body(context, report)
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$EMAIL?subject=${Uri.encode(SUBJECT)}&body=${Uri.encode(body)}"))
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(EMAIL))
            .putExtra(Intent.EXTRA_SUBJECT, SUBJECT)
            .putExtra(Intent.EXTRA_TEXT, body)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, tr(R.string.toast_no_email, EMAIL), Toast.LENGTH_LONG).show()
        }
    }

    fun openGitHub(context: Context, report: ProblemReport) {
        val url = Uri.parse("$REPOSITORY/issues/new").buildUpon()
            .appendQueryParameter("title", SUBJECT)
            .appendQueryParameter("body", body(context, report))
            .build()
        openLink(context, url.toString())
    }

    fun openLink(context: Context, url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, tr(R.string.toast_no_browser, url), Toast.LENGTH_LONG).show()
        }
    }
}

/** "Send Email / Open an Issue on GitHub" for [report]. */
@Composable
fun ReportProblemDialog(report: ProblemReport, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.report_problem)) },
        text = {
            Column {
                Text(stringResource(R.string.report_message))
                TextButton(onClick = { Reports.sendEmail(context, report); onDismiss() }) { Text(stringResource(R.string.send_email)) }
                TextButton(onClick = { Reports.openGitHub(context, report); onDismiss() }) { Text(stringResource(R.string.open_issue)) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
