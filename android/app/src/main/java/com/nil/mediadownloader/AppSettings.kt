package com.nil.mediadownloader

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * App-wide settings, like app_settings.py on desktop. Each field is Compose state,
 * so the whole UI updates instantly when one changes, and every change is saved.
 */
object AppSettings {
    private lateinit var prefs: SharedPreferences

    /** null = follow the phone's theme until the user flips the switch. */
    var darkMode by mutableStateOf<Boolean?>(null)
        private set
    var embedThumbnail by mutableStateOf(false)
        private set
    var downloadSubtitles by mutableStateOf(false)
        private set
    var subtitleLangs by mutableStateOf("en")
        private set
    var backgroundDownloads by mutableStateOf(true)
        private set
    /** How many files each tab downloads at the same time (1–5). */
    var maxConcurrent by mutableIntStateOf(1)
        private set
    /** Total download speed cap in KB/s; 0 = unlimited. */
    var speedLimitKB by mutableIntStateOf(0)
        private set
    /** Offer to download a YouTube link found on the clipboard when the app opens. */
    var clipboardDetect by mutableStateOf(true)
        private set
    /** "Downloads finished" notification when the app isn't on screen. */
    var notifyWhenDone by mutableStateOf(true)
        private set
    /** Spotify tab: how sure (0–100) a YouTube match must be to download without asking. */
    var spotifyConfidence by mutableIntStateOf(80)
        private set
    /** Spotify tab: comma-separated tags added to each YouTube search. Empty = the defaults. */
    var spotifySearchTags by mutableStateOf(DEFAULT_SPOTIFY_TAGS)
        private set

    const val DEFAULT_SPOTIFY_TAGS = "(Official Music Video), (Official Lyric Video)"

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        darkMode = if (prefs.contains("dark_mode")) prefs.getBoolean("dark_mode", false) else null
        embedThumbnail = prefs.getBoolean("embed_thumbnail", false)
        downloadSubtitles = prefs.getBoolean("download_subtitles", false)
        subtitleLangs = prefs.getString("subtitle_langs", "en") ?: "en"
        backgroundDownloads = prefs.getBoolean("background_downloads", true)
        maxConcurrent = prefs.getInt("max_concurrent", 1).coerceIn(1, 5)
        speedLimitKB = prefs.getInt("speed_limit_kb", 0).coerceAtLeast(0)
        clipboardDetect = prefs.getBoolean("clipboard_detect", true)
        notifyWhenDone = prefs.getBoolean("notify_when_done", true)
        spotifyConfidence = prefs.getInt("spotify_confidence", 80).coerceIn(0, 100)
        spotifySearchTags = prefs.getString("spotify_search_tags", DEFAULT_SPOTIFY_TAGS) ?: DEFAULT_SPOTIFY_TAGS
    }

    fun updateSpotifyConfidence(value: Int) {
        spotifyConfidence = value.coerceIn(0, 100)
        prefs.edit().putInt("spotify_confidence", spotifyConfidence).apply()
    }

    fun updateSpotifySearchTags(value: String) {
        spotifySearchTags = value
        prefs.edit().putString("spotify_search_tags", value).apply()
    }

    fun updateDarkMode(value: Boolean?) {
        darkMode = value
        prefs.edit().apply { if (value == null) remove("dark_mode") else putBoolean("dark_mode", value) }.apply()
    }

    fun updateEmbedThumbnail(value: Boolean) {
        embedThumbnail = value
        prefs.edit().putBoolean("embed_thumbnail", value).apply()
    }

    fun updateDownloadSubtitles(value: Boolean) {
        downloadSubtitles = value
        prefs.edit().putBoolean("download_subtitles", value).apply()
    }

    fun updateSubtitleLangs(value: String) {
        subtitleLangs = value
        prefs.edit().putString("subtitle_langs", value).apply()
    }

    fun updateBackgroundDownloads(value: Boolean) {
        backgroundDownloads = value
        prefs.edit().putBoolean("background_downloads", value).apply()
    }

    fun updateMaxConcurrent(value: Int) {
        maxConcurrent = value.coerceIn(1, 5)
        prefs.edit().putInt("max_concurrent", maxConcurrent).apply()
    }

    fun updateSpeedLimitKB(value: Int) {
        speedLimitKB = value.coerceAtLeast(0)
        prefs.edit().putInt("speed_limit_kb", speedLimitKB).apply()
    }

    fun updateClipboardDetect(value: Boolean) {
        clipboardDetect = value
        prefs.edit().putBoolean("clipboard_detect", value).apply()
    }

    fun updateNotifyWhenDone(value: Boolean) {
        notifyWhenDone = value
        prefs.edit().putBoolean("notify_when_done", value).apply()
    }
}

/**
 * Tab state and the coroutine scope downloads run in. Living here (not inside the screen)
 * means a download keeps going if you leave the app, and its progress is still there
 * when you come back.
 */
object AppState {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val youtube = YouTubeState()
    val otherSites = OtherSitesState()
    val spotify = SpotifyState()
    val utility = UtilityState()
    /** Every tab's job, in tab order. */
    val tasks get() = listOf(youtube.task, otherSites.task, spotify.task, utility.task)

    var tab by mutableIntStateOf(0)
    var showSettings by mutableStateOf(false)
    var showHistory by mutableStateOf(false)
    var showTerms by mutableStateOf(false)
    /** A shared link that arrived before the Terms were agreed to. */
    var pendingLink: String? = null
    /** True while the app is on screen, so "finished" notifications only show when it isn't. */
    @Volatile var inForeground = false

    val anyRunning: Boolean get() = tasks.any { it.running }

    /** True while a download is waiting for the user to pick something. */
    val anyPrompt: Boolean
        get() = youtube.playlistPrompt != null || otherSites.picker != null ||
            spotify.tracksPrompt != null || spotify.choicePrompt != null || spotify.matchPrompt != null

    fun cancelAll() {
        youtube.cancel()
        otherSites.cancel()
        spotify.cancel()
        utility.cancel()
    }

    /** Sends a link to the right tab and starts it (or queues it if that tab is busy). */
    fun route(context: Context, link: String) {
        if (YouTubeLinks.isYouTube(link)) {
            tab = 0
            youtube.enqueue(context, link)
        } else if (SpotifyLinks.isSpotify(link)) {
            tab = 2
            spotify.enqueue(context, link)
        } else {
            tab = 1
            otherSites.enqueue(context, link)
        }
    }
}
