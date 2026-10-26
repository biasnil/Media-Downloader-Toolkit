package com.nil.mediadownloader

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

private val TABS = listOf(R.string.tab_youtube, R.string.tab_other, R.string.tab_spotify, R.string.tab_utility)
private val TAB_ICONS = listOf(R.drawable.ic_youtube, R.drawable.ic_globe, R.drawable.ic_spotify, R.drawable.ic_tools)

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
        // Icon colors follow the theme (AppTheme)
        // Paint the window in the saved theme's color, so a light theme doesn't flash dark
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val theme = AppSettings.theme ?: if (systemDark) ThemeChoice.DARK else ThemeChoice.LIGHT
        window.setBackgroundDrawable(ColorDrawable(theme.palette.background.toArgb()))
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        setContent {
            AppTheme {
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

                MainLayout()
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

/** Phones: title + pill tabs. Tablets and wide windows: a side menu, like the tablet mockup. */
@Composable
private fun MainLayout() {
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .systemBarsPadding()
            .displayCutoutPadding()
    ) {
        if (maxWidth >= 840.dp) {
            Row(Modifier.fillMaxSize()) {
                SideNav()
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(TABS[AppState.tab]),
                        fontSize = 30.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(start = 24.dp, top = 22.dp, bottom = 4.dp),
                    )
                    CompositionLocalProvider(LocalWideLayout provides true) {
                        TabContent(Modifier.padding(horizontal = 8.dp))
                    }
                }
                ActivityPanel()
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f)) {
                    if (AppState.showActivity) {
                        ActivityScreen()
                    } else {
                        PhoneHeader()
                        PillTabs(TABS.map { stringResource(it) }, AppState.tab, { AppState.tab = it }, Modifier.padding(top = 4.dp, bottom = 4.dp), icons = TAB_ICONS)
                        TabContent()
                    }
                }
                BottomBar()
            }
        }
    }
}

@Composable
private fun TabContent(modifier: Modifier = Modifier) {
    when (AppState.tab) {
        0 -> YouTubeTab(AppState.youtube, AppState.scope, modifier)
        1 -> OtherSitesTab(AppState.otherSites, AppState.scope, modifier)
        2 -> SpotifyTab(AppState.spotify, AppState.scope, modifier)
        else -> UtilityTab(AppState.utility, AppState.scope, modifier)
    }
}

@Composable
private fun PhoneHeader() {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Media Downloader",
            fontSize = 26.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.5).sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Phones: Download (the tabs), Activity, History, Settings, like the mockup. */
@Composable
private fun BottomBar() {
    val colors = MaterialTheme.colorScheme
    HorizontalDivider(color = colors.outline.copy(alpha = 0.5f))
    Row(
        Modifier
            .fillMaxWidth()
            .background(LocalPalette.current.sidebar)
            .height(68.dp)
            .selectableGroup(),
    ) {
        BarItem(R.drawable.ic_download, stringResource(R.string.nav_download), selected = !AppState.showActivity) {
            AppState.showActivity = false
        }
        BarItem(R.drawable.ic_activity, stringResource(R.string.activity), selected = AppState.showActivity, dot = AppState.anyRunning) {
            AppState.showActivity = true
        }
        BarItem(R.drawable.ic_history, stringResource(R.string.history_title), selected = false) { AppState.showHistory = true }
        BarItem(R.drawable.ic_settings, stringResource(R.string.settings_title), selected = false) { AppState.showSettings = true }
    }
}

@Composable
private fun RowScope.BarItem(icon: Int, label: String, selected: Boolean, dot: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tint = if (selected) colors.primary else colors.onSurfaceVariant
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box {
            Icon(painterResource(icon), null, tint = tint, modifier = Modifier.size(22.dp))
            // Something is downloading
            if (dot) Box(Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-2).dp).size(8.dp).background(colors.primary, CircleShape))
        }
        Text(
            label,
            color = tint,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Phones: the big ring, Up next and the log for whichever tab is picked at the top. */
@Composable
private fun ActivityScreen() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            stringResource(R.string.activity),
            fontSize = 26.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.5).sp,
            modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 10.dp),
        )
        PillTabs(TABS.map { stringResource(it) }, AppState.tab, { AppState.tab = it }, Modifier.padding(bottom = 18.dp), icons = TAB_ICONS)
        Column(Modifier.padding(horizontal = 20.dp)) {
            key(AppState.tab) {
                ProgressAndLog(
                    AppState.tasks[AppState.tab], AppState.scope,
                    showUpdate = AppState.tab != 3, inPanel = true, onCancel = { AppState.cancelTab(AppState.tab) },
                )
            }
        }
    }
}

@Composable
private fun SideNav() {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .width(232.dp)
            .fillMaxHeight()
            .background(LocalPalette.current.sidebar)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.padding(start = 8.dp, bottom = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).background(colors.primary, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(painterResource(R.drawable.ic_download), null, tint = colors.onPrimary, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.width(10.dp))
            Text("Media\nDownloader", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 19.sp)
        }
        NavHeading(stringResource(R.string.nav_sources))
        TABS.forEachIndexed { i, label ->
            NavItem(TAB_ICONS[i], stringResource(label), selected = AppState.tab == i) { AppState.tab = i }
        }
        NavHeading(stringResource(R.string.nav_library), Modifier.padding(top = 18.dp))
        NavItem(R.drawable.ic_history, stringResource(R.string.history_title), selected = false) { AppState.showHistory = true }
        NavItem(R.drawable.ic_settings, stringResource(R.string.settings_title), selected = false) { AppState.showSettings = true }
    }
}

/** Tablets: the open tab's status and log, on the right like the mockup's queue panel. */
@Composable
private fun ActivityPanel() {
    val task = AppState.tasks[AppState.tab]
    Column(
        Modifier
            .width(340.dp)
            .fillMaxHeight()
            .background(LocalPalette.current.sidebar)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 22.dp),
    ) {
        Text(
            stringResource(R.string.activity),
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 14.dp),
        )
        // key: each tab keeps its own log scroll and fold state
        key(AppState.tab) {
            ProgressAndLog(
                task, AppState.scope,
                showUpdate = AppState.tab != 3, inPanel = true, onCancel = { AppState.cancelTab(AppState.tab) },
            )
        }
    }
}

@Composable
private fun NavHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun NavItem(icon: Int, label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) colors.surfaceVariant else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, tint = if (selected) colors.primary else colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            color = if (selected) colors.onBackground else colors.onSurfaceVariant,
        )
    }
}
