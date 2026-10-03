package com.nil.mediadownloader

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
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

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        darkMode = if (prefs.contains("dark_mode")) prefs.getBoolean("dark_mode", false) else null
        embedThumbnail = prefs.getBoolean("embed_thumbnail", false)
        downloadSubtitles = prefs.getBoolean("download_subtitles", false)
        subtitleLangs = prefs.getString("subtitle_langs", "en") ?: "en"
        backgroundDownloads = prefs.getBoolean("background_downloads", true)
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
}
