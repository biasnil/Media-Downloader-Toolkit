package com.nil.mediadownloader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.LocalDate

/**
 * Spots a YouTube or Spotify link on the clipboard when the app comes to the front (e.g. after
 * Share → Copy link) and offers to download it. Setting: Clipboard → Detect copied links.
 */
object ClipboardWatcher {
    /** Set when a new YouTube link is found; MainActivity asks about it while this is non-null. */
    var foundLink by mutableStateOf<String?>(null)

    private fun prefs(context: Context) = context.getSharedPreferences("clipboard", Context.MODE_PRIVATE)

    /**
     * Looks at the clipboard once per copy, so the same link is never offered twice. Android only
     * lets apps read the clipboard while they have focus, so call this from onWindowFocusChanged.
     */
    fun check(context: Context) {
        if (!AppSettings.clipboardDetect || foundLink != null || snoozedToday(context)) return
        val board = context.getSystemService(ClipboardManager::class.java) ?: return
        // Reading the clip shows Android 12+'s "pasted from your clipboard" message, so check the
        // description first (that doesn't count as reading) and only read each copy once
        val description = board.primaryClipDescription ?: return
        val prefs = prefs(context)
        if (description.timestamp == prefs.getLong("last_clip", 0L)) return
        prefs.edit().putLong("last_clip", description.timestamp).apply()
        if (!description.hasMimeType("text/*")) return

        val text = board.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString() ?: return
        foundLink = supportedLink(text)
    }

    /** "Don't ask again today" */
    fun snoozeForToday(context: Context) {
        prefs(context).edit().putLong("snoozed_day", LocalDate.now().toEpochDay()).apply()
        foundLink = null
    }

    /** Copies [text] without it being offered back as a download when the app regains focus. */
    fun copy(context: Context, text: String) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(tr(R.string.clip_label), text))
        markSeen(context)
    }

    /** Treats what's on the clipboard now as already handled. */
    fun markSeen(context: Context) {
        val stamp = context.getSystemService(ClipboardManager::class.java)?.primaryClipDescription?.timestamp ?: return
        prefs(context).edit().putLong("last_clip", stamp).apply()
    }

    private fun snoozedToday(context: Context) =
        prefs(context).getLong("snoozed_day", -1L) == LocalDate.now().toEpochDay()

    /** First YouTube video/playlist link, or Spotify track/playlist/album link, in the text. */
    private fun supportedLink(text: String): String? =
        allLinks(text).firstOrNull { link ->
            (YouTubeLinks.isYouTube(link) && (YouTubeLinks.videoId(link) != null || YouTubeLinks.playlistId(link) != null)) ||
                SpotifyLinks.parse(link) != null
        }
}

private val LINK = Regex("""https?://[^\s<>"']+""")

private fun allLinks(text: String): List<String> =
    LINK.findAll(text).map { it.value.trimEnd('.', ',', ')', ']', '!', '?') }.toList()

/** The first web link in shared text (apps often share "Title https://..."). */
fun firstLink(text: String): String? = allLinks(text).firstOrNull()
