package com.nil.mediadownloader

import android.app.Activity
import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** The themes in Settings. [key] is what's saved. */
enum class ThemeChoice(val key: String, @param:StringRes val label: Int) {
    DEFAULT("default", R.string.theme_default),
    LIGHT("light", R.string.light),
    DARK("dark", R.string.dark_theme),
    SAKURA("sakura", R.string.theme_sakura),
    BLUE("blue", R.string.theme_blue);

    val palette: Palette get() = PALETTES.getValue(this)

    companion object {
        fun of(key: String?): ThemeChoice? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Everything a theme decides. The Material colors cover most of the UI; the rest is for
 * things Material has no slot for (the side menu, the playlist cover when there's no thumbnail).
 */
class Palette(
    val isDark: Boolean,
    val background: Color,
    /** Cards */
    val surface: Color,
    /** Text boxes, unselected chips */
    val field: Color,
    val outline: Color,
    val text: Color,
    val subtext: Color,
    val accent: Color,
    val onAccent: Color,
    val error: Color,
    val success: Color,
    /** Tablet side menu */
    val sidebar: Color,
    /** Stand-in cover art, top-left to bottom-right */
    val art: List<Color>,
) {
    val scheme: ColorScheme = if (isDark) darkColorScheme(
        primary = accent, onPrimary = onAccent, secondary = accent, onSecondary = onAccent, tertiary = accent,
        primaryContainer = field, onPrimaryContainer = text, secondaryContainer = field, onSecondaryContainer = text,
        background = background, onBackground = text, surface = background, onSurface = text,
        surfaceVariant = field, onSurfaceVariant = subtext, outline = outline, outlineVariant = outline,
        surfaceContainerLowest = background, surfaceContainerLow = sidebar, surfaceContainer = surface,
        surfaceContainerHigh = surface, surfaceContainerHighest = field,
        error = error, onError = onAccent,
    ) else lightColorScheme(
        primary = accent, onPrimary = onAccent, secondary = accent, onSecondary = onAccent, tertiary = accent,
        primaryContainer = field, onPrimaryContainer = text, secondaryContainer = field, onSecondaryContainer = text,
        background = background, onBackground = text, surface = background, onSurface = text,
        surfaceVariant = field, onSurfaceVariant = subtext, outline = outline, outlineVariant = outline,
        surfaceContainerLowest = background, surfaceContainerLow = sidebar, surfaceContainer = surface,
        surfaceContainerHigh = surface, surfaceContainerHighest = field,
        error = error, onError = Color.White,
    )
}

private val PALETTES = mapOf(
    // Purple-black with magenta, like the redesign mockup
    ThemeChoice.DEFAULT to Palette(
        isDark = true,
        background = Color(0xFF0F0B1A), surface = Color(0xFF1C1529), field = Color(0xFF2A1F3F),
        outline = Color(0xFF3A2E52), text = Color(0xFFF4F1FA), subtext = Color(0xFFA79FBC),
        accent = Color(0xFFE5197A), onAccent = Color.White, error = Color(0xFFFF6B81), success = Color(0xFF4ADE80),
        sidebar = Color(0xFF15101F), art = listOf(Color(0xFF3B1C6E), Color(0xFF8A1E6E), Color(0xFFFF4FA3)),
    ),
    // The YouTube look the app had before, tidied up
    ThemeChoice.DARK to Palette(
        isDark = true,
        background = Color(0xFF0F0F0F), surface = Color(0xFF1C1C1C), field = Color(0xFF272727),
        outline = Color(0xFF3F3F3F), text = Color.White, subtext = Color(0xFFAAAAAA),
        accent = Color(0xFFFF0000), onAccent = Color.White, error = Color(0xFFFF5A5A), success = Color(0xFF4ADE80),
        sidebar = Color(0xFF161616), art = listOf(Color(0xFF3A0A0A), Color(0xFFB00000), Color(0xFFFF4040)),
    ),
    ThemeChoice.LIGHT to Palette(
        isDark = false,
        background = Color.White, surface = Color(0xFFF4F4F6), field = Color(0xFFEBEBEE),
        outline = Color(0xFFD9D9DE), text = Color(0xFF0F0F0F), subtext = Color(0xFF5F5F66),
        // A little darker than YouTube red so red text stays readable on white
        accent = Color(0xFFD40000), onAccent = Color.White, error = Color(0xFFC62828), success = Color(0xFF2E7D32),
        sidebar = Color(0xFFF7F7F8), art = listOf(Color(0xFFC40000), Color(0xFFFF4D4D), Color(0xFFFF9A7A)),
    ),
    // Cherry blossom: soft pink paper with rose accents
    ThemeChoice.SAKURA to Palette(
        isDark = false,
        background = Color(0xFFFFF6F8), surface = Color.White, field = Color(0xFFFCE4EC),
        outline = Color(0xFFF2C4D4), text = Color(0xFF3A1E2B), subtext = Color(0xFF85566A),
        accent = Color(0xFFC2386C), onAccent = Color.White, error = Color(0xFFB3261E), success = Color(0xFF2E7D4F),
        sidebar = Color(0xFFFFEEF3), art = listOf(Color(0xFFF8BBD0), Color(0xFFF48FB1), Color(0xFFC2386C)),
    ),
    ThemeChoice.BLUE to Palette(
        isDark = true,
        background = Color(0xFF0A1020), surface = Color(0xFF131C33), field = Color(0xFF1C2846),
        outline = Color(0xFF2C3A5E), text = Color(0xFFEEF3FF), subtext = Color(0xFF9AA8C7),
        accent = Color(0xFF2F6FEB), onAccent = Color.White, error = Color(0xFFFF6B6B), success = Color(0xFF4ADE80),
        sidebar = Color(0xFF0E1628), art = listOf(Color(0xFF0B2A6B), Color(0xFF1E4FD8), Color(0xFF4FB3FF)),
    ),
)

/** The palette in use, for the few colors MaterialTheme has no slot for. */
val LocalPalette = staticCompositionLocalOf { ThemeChoice.DEFAULT.palette }

/** The theme picked in Settings; "Follow phone" picks Light or Dark from the phone's setting. */
@Composable
fun currentTheme(): ThemeChoice =
    AppSettings.theme ?: if (isSystemInDarkTheme()) ThemeChoice.DARK else ThemeChoice.LIGHT

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val palette = currentTheme().palette
    val view = LocalView.current
    if (!view.isInEditMode) {
        // Dark status-bar icons on light themes, white ones on dark themes
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !palette.isDark
                isAppearanceLightNavigationBars = !palette.isDark
            }
        }
    }
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = palette.scheme) {
            // Text outside a Surface (most of the main screen) would otherwise default to black
            CompositionLocalProvider(LocalContentColor provides palette.text, content = content)
        }
    }
}
