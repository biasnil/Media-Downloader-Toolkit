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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            Downloader.cancel()
            AppState.youtube.playlistPrompt?.answer?.complete(null)
            activeTask?.status = "Cancelling..."
            return START_NOT_STICKY
        }

        // Android requires startForeground() promptly after starting, so always call it first
        val task = activeTask
        ensureChannel(this)
        startForeground(
            ONGOING_ID,
            buildProgress(this, task?.status ?: "Starting...", task?.progress ?: 0f),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (task == null) { stopSelf(); return START_NOT_STICKY } // download already ended
        if (wakeLock == null) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MediaDownloader:download")
                .apply { acquire(6 * 60 * 60 * 1000L) } // safety cap of 6 hours
        }

        // Mirror the tab's status/progress into the notification, at most ~once a second
        watcher?.cancel()
        watcher = scope.launch {
            snapshotFlow { task.status to task.progress }.conflate().collect { (status, progress) ->
                // Don't redraw after the download ended, or a stuck "ongoing" notification could remain
                if (activeTask === task) {
                    notifyIfAllowed(this@DownloadService, ONGOING_ID, buildProgress(this@DownloadService, status, progress))
                }
                delay(1000)
            }
        }
        return START_NOT_STICKY
    }

    // Android 15+ limits this kind of service to 6 hours a day
    override fun onTimeout(startId: Int, fgsType: Int) {
        Downloader.cancel()
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

        /** The tab whose download is running, so the notification can follow it. */
        @Volatile var activeTask: TaskState? = null

        fun start(context: Context, task: TaskState) {
            activeTask = task
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        }

        /** Stops the service and leaves a "finished" notification behind. */
        fun finish(context: Context, summary: String) {
            activeTask = null
            context.stopService(Intent(context, DownloadService::class.java))
            ensureChannel(context)
            val done = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Downloads finished")
                .setContentText(summary)
                .setContentIntent(openAppIntent(context))
                .setAutoCancel(true)
                .build()
            notifyIfAllowed(context, FINISHED_ID, done)
        }

        private fun ensureChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Progress of ongoing downloads" }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        private fun buildProgress(context: Context, status: String, progress: Float) =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Downloading...")
                .setContentText(status)
                .setProgress(100, (progress * 100).toInt(), progress <= 0f)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setContentIntent(openAppIntent(context))
                .addAction(
                    0, "Cancel",
                    PendingIntent.getService(
                        context, 1,
                        Intent(context, DownloadService::class.java).setAction(ACTION_CANCEL),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                )
                .build()

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
