package com.nil.mediadownloader

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

private val TABS = listOf(R.string.tab_youtube, R.string.tab_other, R.string.tab_spotify, R.string.tab_utility)

class MainActivity : ComponentActivity() {
    // Android 12 and older: show the screen in the language picked in Settings
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Lang.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.refresh(this) // first: everything below may show text
        AppSettings.init(applicationContext)
        HistoryStore.init(applicationContext)
        if (Terms.needsAgreement(this)) AppState.showTerms = true
        if (savedInstanceState == null) handleShare(intent)
        // White status-bar icons on top of the red header
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent {
            val dark = AppSettings.darkMode ?: isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) DarkColors else LightColors) {
                val context = LocalContext.current

                AskForNotificationPermission()

                // Without the background notification, fall back to keeping the screen on
                val view = LocalView.current
                SideEffect { view.keepScreenOn = AppState.anyRunning && !AppSettings.backgroundDownloads }

                if (AppState.showSettings) SettingsDialog(onClose = { AppState.showSettings = false })
                if (AppState.showHistory) {
                    HistoryScreen(
                        onDownloadAgain = { link -> AppState.showHistory = false; AppState.route(context, link) },
                        onClose = { AppState.showHistory = false },
                    )
                }
                // Here rather than in the tabs, so they show whichever tab is open
                AppState.youtube.playlistPrompt?.let { prompt ->
                    PlaylistDialog(
                        prompt = prompt,
                        onConfirm = { prompt.answer.complete(it) },
                        onCancel = { prompt.answer.complete(null) },
                    )
                }
                AppState.otherSites.picker?.let { prompt ->
                    PlaylistDialog(
                        prompt = prompt,
                        onConfirm = { prompt.answer.complete(it) },
                        onCancel = { prompt.answer.complete(null) },
                    )
                }
                SpotifyPrompts(AppState.spotify)
                ClipboardWatcher.foundLink?.let { link -> ClipboardQuestion(context, link) }
                if (AppState.showTerms) TermsScreen(onAgree = { agreed(context) })

                Scaffold(
                    topBar = {
                        AppHeader(
                            dark,
                            onHistory = { AppState.showHistory = true },
                            onSettings = { AppState.showSettings = true },
                        )
                    },
                    containerColor = MaterialTheme.colorScheme.background,
                ) { padding ->
                    Column(Modifier.padding(padding)) {
                        PillTabs(TABS.map { stringResource(it) }, AppState.tab, { AppState.tab = it }, Modifier.padding(top = 10.dp))
                        when (AppState.tab) {
                            0 -> YouTubeTab(AppState.youtube, AppState.scope)
                            1 -> OtherSitesTab(AppState.otherSites, AppState.scope)
                            2 -> SpotifyTab(AppState.spotify, AppState.scope)
                            else -> UtilityTab(AppState.utility, AppState.scope)
                        }
                    }
                }
            }
        }
    }

    // singleTask: a link shared while the app is open arrives here
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onStart() {
        super.onStart()
        AppState.inForeground = true
    }

    override fun onStop() {
        AppState.inForeground = false
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (Terms.needsAgreement(this)) AppState.showTerms = true // every 10 days
    }

    // Android only lets apps read the clipboard while they have focus, so check here rather than in onResume
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !AppState.showTerms && !AppState.showSettings && !AppState.showHistory && !AppState.anyPrompt) {
            ClipboardWatcher.check(this)
        }
    }

    /** "Media Downloader" in the Share sheet: start downloading the shared link. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        intent.removeExtra(Intent.EXTRA_TEXT) // so it isn't started twice
        val link = firstLink(text)
        if (link == null) {
            Toast.makeText(this, tr(R.string.toast_no_link), Toast.LENGTH_LONG).show()
            return
        }
        AppState.showSettings = false
        AppState.showHistory = false
        ClipboardWatcher.foundLink = null
        if (Terms.needsAgreement(this)) {
            AppState.pendingLink = link
            AppState.showTerms = true
        } else {
            AppState.route(this, link)
        }
    }

    private fun agreed(context: Context) {
        Terms.agree(context)
        AppState.showTerms = false
        AppState.pendingLink?.let { link ->
            AppState.pendingLink = null
            AppState.route(context, link)
        }
    }
}

/** Copied YouTube or Spotify link: ask before downloading it. */
@Composable
private fun ClipboardQuestion(context: Context, link: String) {
    AlertDialog(
        onDismissRequest = { ClipboardWatcher.foundLink = null },
        title = {
            Text(stringResource(if (SpotifyLinks.isSpotify(link)) R.string.clip_question_spotify else R.string.clip_question_youtube))
        },
        text = { Text(link) },
        confirmButton = {
            TextButton(onClick = {
                ClipboardWatcher.foundLink = null
                AppState.route(context, link)
            }) { Text(stringResource(R.string.download)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { ClipboardWatcher.snoozeForToday(context) }) { Text(stringResource(R.string.not_today)) }
                TextButton(onClick = { ClipboardWatcher.foundLink = null }) { Text(stringResource(R.string.not_now)) }
            }
        },
    )
}

/** Android 13+ needs permission to show download notifications. Asked once. */
@Composable
private fun AskForNotificationPermission() {
    if (Build.VERSION.SDK_INT < 33) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted && (AppSettings.backgroundDownloads || AppSettings.notifyWhenDone)) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun AppHeader(dark: Boolean, onHistory: () -> Unit, onSettings: () -> Unit) {
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onHistory) {
            Icon(painterResource(R.drawable.ic_history), contentDescription = stringResource(R.string.history_title), tint = Color.White)
        }
        // Gear icon, like desktop (︎ keeps it a plain symbol instead of an emoji)
        TextButton(onClick = onSettings, contentPadding = PaddingValues(horizontal = 6.dp)) {
            Text("⚙︎", color = Color.White, fontSize = 22.sp)
        }
        Text(stringResource(R.string.dark), color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(end = 6.dp))
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
