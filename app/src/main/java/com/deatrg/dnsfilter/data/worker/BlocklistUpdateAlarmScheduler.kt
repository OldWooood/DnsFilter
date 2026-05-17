package com.deatrg.dnsfilter.data.worker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.deatrg.dnsfilter.AppLog
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class BlocklistUpdateAlarmScheduler(
    private val context: Context
) {
    companion object {
        private const val TAG = "BlocklistUpdateAlarmScheduler"
        const val REQUEST_CODE_DAILY = 1001
        const val REQUEST_CODE_IMMEDIATE = 1002
        const val ACTION_UPDATE_BLOCKLIST = "com.deatrg.dnsfilter.ACTION_UPDATE_BLOCKLIST"
    }

    fun scheduleDailyUpdate() {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = createDailyPendingIntent()

        alarmManager.cancel(pendingIntent)

        val triggerMillis = calculateNextNoon()
        val alarmInfo = AlarmManager.AlarmClockInfo(triggerMillis, null)
        alarmManager.setAlarmClock(alarmInfo, pendingIntent)

        AppLog.d(
            TAG,
            "Scheduled daily update at ${
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(triggerMillis))
            }"
        )
    }

    fun cancelDailyUpdate() {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(createDailyPendingIntent())
        AppLog.d(TAG, "Cancelled daily update alarm")
    }

    fun triggerImmediateUpdate() {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(ACTION_UPDATE_BLOCKLIST).apply {
            `package` = context.packageName
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_IMMEDIATE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        alarmManager.setExact(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + 1000,
            pendingIntent
        )
        AppLog.d(TAG, "Triggered immediate blocklist update")
    }

    private fun createDailyPendingIntent(): PendingIntent {
        val intent = Intent(ACTION_UPDATE_BLOCKLIST).apply {
            `package` = context.packageName
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_DAILY,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun calculateNextNoon(): Long {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (calendar.timeInMillis <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_MONTH, 1)
        }

        return calendar.timeInMillis
    }
}
