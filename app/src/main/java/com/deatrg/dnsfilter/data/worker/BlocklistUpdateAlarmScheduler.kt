package com.deatrg.dnsfilter.data.worker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.ZonedDateTime

class BlocklistUpdateAlarmScheduler(context: Context) {

    companion object {
        const val ACTION_UPDATE_BLOCKLISTS = "com.deatrg.dnsfilter.UPDATE_BLOCKLISTS"
        private const val REQUEST_CODE = 2001
        private const val UPDATE_HOUR = 12

        internal fun nextUpdateTime(now: ZonedDateTime): ZonedDateTime {
            var next = now.toLocalDate()
                .atTime(UPDATE_HOUR, 0)
                .atZone(now.zone)
            if (!next.isAfter(now)) {
                next = now.toLocalDate()
                    .plusDays(1)
                    .atTime(UPDATE_HOUR, 0)
                    .atZone(now.zone)
            }
            return next
        }
    }

    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)

    fun scheduleNext() {
        val pendingIntent = updatePendingIntent()
        val triggerAtMillis = nextUpdateTime(ZonedDateTime.now()).toInstant().toEpochMilli()

        // A daily blocklist refresh does not need exact-alarm special access. This API can
        // still wake the app from idle, while Android may batch it slightly for battery life.
        alarmManager.cancel(pendingIntent)
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAtMillis,
            pendingIntent
        )
    }

    private fun updatePendingIntent(): PendingIntent {
        val intent = Intent(appContext, BlocklistUpdateAlarmReceiver::class.java).apply {
            action = ACTION_UPDATE_BLOCKLISTS
        }
        return PendingIntent.getBroadcast(
            appContext,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
