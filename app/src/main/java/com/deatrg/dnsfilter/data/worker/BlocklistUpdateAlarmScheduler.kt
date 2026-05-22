package com.deatrg.dnsfilter.data.worker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
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
        private const val DAILY_UPDATE_HOUR = 12
        private const val DAILY_UPDATE_MINUTE = 0
    }

    fun scheduleDailyUpdate() {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = createDailyPendingIntent()

        alarmManager.cancel(pendingIntent)

        val triggerMillis = calculateNextDailyTime()
        scheduleAlarm(alarmManager, triggerMillis, pendingIntent)

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
        val intent = createUpdateIntent()
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_IMMEDIATE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        scheduleAlarm(alarmManager, System.currentTimeMillis() + 1000, pendingIntent)
        AppLog.d(TAG, "Triggered immediate blocklist update")
    }

    private fun createDailyPendingIntent(): PendingIntent {
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_DAILY,
            createUpdateIntent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createUpdateIntent(): Intent {
        return Intent(context, BlocklistUpdateAlarmReceiver::class.java).apply {
            action = ACTION_UPDATE_BLOCKLIST
        }
    }

    private fun scheduleAlarm(
        alarmManager: AlarmManager,
        triggerMillis: Long,
        pendingIntent: PendingIntent
    ) {
        val canScheduleExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()

        if (canScheduleExact) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerMillis,
                        pendingIntent
                    )
                } else {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
                }
                return
            } catch (e: SecurityException) {
                AppLog.e(TAG, "Exact alarm permission denied, falling back to inexact alarm", e)
            } catch (e: Exception) {
                AppLog.e(TAG, "Failed to schedule exact alarm, falling back to inexact alarm", e)
            }
        } else {
            AppLog.w(TAG, "Exact alarm permission is not granted, scheduling inexact alarm")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
        }
    }

    private fun calculateNextDailyTime(): Long {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, DAILY_UPDATE_HOUR)
            set(Calendar.MINUTE, DAILY_UPDATE_MINUTE)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (calendar.timeInMillis <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_MONTH, 1)
        }

        return calendar.timeInMillis
    }
}
