package com.nil.mediadownloader

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshotFlow
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

/**
 * Foreground service that runs while a download is in progress. It shows the ongoing
 * progress notification (with a Cancel button) and tells Android not to kill or sleep
 * the app, so downloads keep going with the screen off or while you use other apps.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Lang.refresh(this) // the Cancel button can start this before any screen has
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            AppState.cancelAll()
            return START_NOT_STICKY
        }

        // Android requires startForeground() promptly after starting, so always call it first
        ensureChannel(this)
        startForeground(ONGOING_ID, buildProgress(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (activeTasks.isEmpty()) { stopSelf(); return START_NOT_STICKY } // downloads already ended
        if (wakeLock == null) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MediaDownloader:download")
                .apply { acquire(6 * 60 * 60 * 1000L) } // safety cap of 6 hours
        }

        // Mirror the tabs' status/progress into the notification, at most ~once a second
        if (watcher == null) {
            watcher = scope.launch {
                snapshotFlow { activeTasks.map { it.status to it.progress } }.conflate().collect {
                    // Don't redraw after downloads ended, or a stuck "ongoing" notification could remain
                    if (activeTasks.isNotEmpty()) notifyIfAllowed(this@DownloadService, ONGOING_ID, buildProgress(this@DownloadService))
                    delay(1000)
                }
            }
        }
        return START_NOT_STICKY
    }

    // Android 15+ limits this kind of service to 6 hours a day
    override fun onTimeout(startId: Int, fgsType: Int) {
        AppState.cancelAll()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val ONGOING_ID = 1
        private const val FINISHED_ID = 2
        private const val ACTION_CANCEL = "com.nil.mediadownloader.CANCEL"

        /** The tabs whose downloads are running, so the notification can follow them. */
        val activeTasks = mutableStateListOf<TaskState>()

        fun start(context: Context, task: TaskState) {
            if (task !in activeTasks) activeTasks += task
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        }

        /**
         * Stops following [task] (and stops the service once no tab is downloading), then
         * leaves a "finished" notification if that's turned on and the app isn't on screen.
         */
        fun finish(context: Context, task: TaskState) {
            if (activeTasks.remove(task) && activeTasks.isEmpty()) {
                context.stopService(Intent(context, DownloadService::class.java))
            }
            if (!AppSettings.notifyWhenDone || AppState.inForeground) return
            ensureChannel(context)
            val done = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(tr(R.string.notif_finished, task.name))
                .setContentText(task.status)
                .setContentIntent(openAppIntent(context))
                .setAutoCancel(true)
                .build()
            // One per tab, so finishing both doesn't hide the first
            notifyIfAllowed(context, FINISHED_ID + AppState.tasks.indexOf(task).coerceAtLeast(0), done)
        }

        private fun ensureChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL_ID, tr(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
                .apply { description = tr(R.string.channel_desc) }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        private fun buildProgress(context: Context): android.app.Notification {
            val tasks = activeTasks.toList()
            val text = when {
                tasks.isEmpty() -> tr(R.string.starting)
                tasks.size == 1 -> tasks[0].status
                else -> tasks.joinToString("\n") { "${it.name}: ${it.status}" }
            }
            val progress = if (tasks.isEmpty()) 0f else tasks.map { it.progress }.average().toFloat()
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(tr(R.string.notif_downloading))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setProgress(100, (progress * 100).toInt(), progress <= 0f)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setContentIntent(openAppIntent(context))
                .addAction(
                    0, tr(R.string.cancel),
                    PendingIntent.getService(
                        context, 1,
                        Intent(context, DownloadService::class.java).setAction(ACTION_CANCEL),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                )
                .build()
        }

        private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        private fun notifyIfAllowed(context: Context, id: Int, notification: android.app.Notification) {
            val allowed = Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (allowed) NotificationManagerCompat.from(context).notify(id, notification)
        }
    }
}
