package com.deatrg.dnsfilter.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import com.deatrg.dnsfilter.R
import com.deatrg.dnsfilter.ui.MainActivity

/**
 * VPN 前台服务的通知渠道与通知构建。
 */
internal object VpnNotifications {

    const val CHANNEL_ID = "dnsfilter_channel"
    const val NOTIFICATION_ID = 1

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notification_channel_description)
        }

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    fun buildServiceNotification(context: Context): Notification {
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(context, DnsVpnService::class.java).apply {
            action = DnsVpnService.ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            context,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(context, android.R.drawable.ic_media_pause),
            context.getString(R.string.notification_action_stop),
            stopPendingIntent
        ).build()

        return Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(context.getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(pendingIntent)
            .addAction(stopAction)
            .setOngoing(true)
            .build()
    }
}
