package com.deatrg.dnsfilter.data.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.deatrg.dnsfilter.AppLog
import com.deatrg.dnsfilter.R
import com.deatrg.dnsfilter.ServiceLocator
import com.deatrg.dnsfilter.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class BlocklistUpdateService : Service() {

    companion object {
        private const val TAG = "BlocklistUpdateService"
        private const val ACTION_UPDATE = "com.deatrg.dnsfilter.ACTION_UPDATE_BLOCKLIST_SERVICE"
        private const val CHANNEL_ID = "blocklist_update_channel"
        private const val NOTIFICATION_ID = 2
        private val isUpdating = AtomicBoolean(false)

        fun start(context: Context) {
            val intent = Intent(context, BlocklistUpdateService::class.java).apply {
                action = ACTION_UPDATE
            }
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                AppLog.e(TAG, "Failed to start blocklist update service", e)
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_UPDATE) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (!isUpdating.compareAndSet(false, true)) {
            AppLog.d(TAG, "Blocklist update already running")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, createNotification())

        scope.launch {
            try {
                AppLog.d(TAG, "Starting blocklist update")
                val repository = ServiceLocator.provideFilterListRepository()
                repository.loadFilterLists()
                val updated = repository.checkAndUpdate()
                AppLog.d(TAG, "Blocklist update completed, updated=$updated")
            } catch (e: Exception) {
                AppLog.e(TAG, "Blocklist update failed", e)
            } finally {
                isUpdating.set(false)
                stopSelf(startId)
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.update_notification_title))
            .setContentText(getString(R.string.update_notification_text))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.update_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.update_notification_channel_description)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }
}
