package com.nil.mediadownloader

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

// Same palette as theme.py on desktop
val YtRed = Color(0xFFFF0000)
private val LightColors = lightColorScheme(
    primary = YtRed, onPrimary = Color.White,
    background = Color(0xFFFFFFFF), surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF2F2F2), outline = Color(0xFFE0E0E0),
    onBackground = Color(0xFF0F0F0F), onSurface = Color(0xFF0F0F0F),
    onSurfaceVariant = Color(0xFF606060),
)
private val DarkColors = darkColorScheme(
    primary = YtRed, onPrimary = Color.White,
    background = Color(0xFF0F0F0F), surface = Color(0xFF0F0F0F),
    surfaceVariant = Color(0xFF272727), outline = Color(0xFF3F3F3F),
    onBackground = Color.White, onSurface = Color.White,
    onSurfaceVariant = Color(0xFFAAAAAA),
)

private val TABS = listOf("YouTube", "Other Sites")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettings.init(applicationContext)
        // White status-bar icons on top of the red header
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent {
            val dark = AppSettings.darkMode ?: isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) DarkColors else LightColors) {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                var showSettings by remember { mutableStateOf(false) }

                AskForNotificationPermission()

                // Without the background notification, fall back to keeping the screen on
                val view = LocalView.current
                SideEffect { view.keepScreenOn = Downloader.busy && !AppSettings.backgroundDownloads }

                if (showSettings) SettingsDialog(onClose = { showSettings = false })

                Scaffold(
                    topBar = { AppHeader(dark, onSettings = { showSettings = true }) },
                    containerColor = MaterialTheme.colorScheme.background,
                ) { padding ->
                    Column(Modifier.padding(padding)) {
                        TabBar(tab) { tab = it }
                        when (tab) {
                            0 -> YouTubeTab(AppState.youtube, AppState.scope)
                            else -> OtherSitesTab(AppState.otherSites, AppState.scope)
                        }
                    }
                }
            }
        }
    }
}

/** Android 13+ needs permission to show the download progress notification. Asked once. */
@Composable
private fun AskForNotificationPermission() {
    if (Build.VERSION.SDK_INT < 33) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted && AppSettings.backgroundDownloads) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
private fun AppHeader(dark: Boolean, onSettings: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(YtRed)
            .statusBarsPadding()
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "▶  Media Downloader",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        // Gear icon, like desktop (\uFE0E keeps it a plain symbol instead of an emoji)
        TextButton(onClick = onSettings) { Text("⚙\uFE0E", color = Color.White, fontSize = 22.sp) }
        Text("Dark", color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(end = 6.dp))
        Switch(
            checked = dark,
            onCheckedChange = { AppSettings.updateDarkMode(it) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = YtRed,
                checkedTrackColor = Color.White,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFFCC0000),
                uncheckedBorderColor = Color.White,
            ),
        )
    }
}

/** Pill-style tab switcher, like the one under the desktop header. */
@Composable
private fun TabBar(selected: Int, onSelect: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.Center) {
        Row(
            Modifier
                .background(colors.surfaceVariant, RoundedCornerShape(8.dp))
                .padding(3.dp)
        ) {
            TABS.forEachIndexed { i, name ->
                val isSelected = i == selected
                Text(
                    name,
                    color = if (isSelected) Color.White else colors.onSurface,
                    fontSize = 14.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isSelected) YtRed else Color.Transparent)
                        .clickable { onSelect(i) }
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
    }
}
