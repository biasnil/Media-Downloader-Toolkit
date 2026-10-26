package com.nil.mediadownloader

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.StringRes

/** Languages the app is translated into, each written in its own language. */
val LANGUAGES = listOf("en" to "English", "fr" to "Français", "zh-Hans" to "简体中文", "ms" to "Bahasa Melayu")

/**
 * The app's language. Android 13+ has its own per-app language setting (also under the phone's
 * Settings → Apps → Media Downloader → Language), which this uses; older versions get the same
 * by wrapping each screen in the chosen language.
 */
object Lang {
    private var localized: Context? = null

    private fun prefs(context: Context) = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    /** The chosen language code, or "" to follow the phone. */
    fun current(context: Context): String =
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags().substringBefore(',')
        } else {
            prefs(context).getString("language", "") ?: ""
        }

    /** Switches language and redraws the screen in it. */
    fun set(activity: Activity, code: String) {
        if (Build.VERSION.SDK_INT >= 33) {
            // Android redraws the screen itself
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                if (code.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(code)
        } else {
            prefs(activity).edit().putString("language", code).apply()
            activity.recreate()
        }
    }

    /** For attachBaseContext on Android 12 and older (13+ applies the language itself). */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val code = prefs(base).getString("language", "") ?: ""
        return if (code.isEmpty()) base else withLocale(base, code)
    }

    /** Call when the app starts (and the screen is recreated after a language change). */
    fun refresh(context: Context) {
        val app = context.applicationContext
        val code = current(app)
        localized = if (code.isEmpty()) app else withLocale(app, code)
    }

    fun text(@StringRes id: Int, vararg args: Any): String {
        val context = localized ?: error("Lang.refresh() must be called first")
        return context.getString(id, *args)
    }

    private fun withLocale(base: Context, code: String): Context {
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList.forLanguageTags(code))
        return base.createConfigurationContext(config)
    }
}

/** Text in the app's language, for code outside the screens (status line, log, notifications). */
fun tr(@StringRes id: Int, vararg args: Any): String = Lang.text(id, *args)

/** The Activity behind a Compose context (dialogs wrap it). */
fun Context.findActivity(): Activity? =
    generateSequence(this) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()
