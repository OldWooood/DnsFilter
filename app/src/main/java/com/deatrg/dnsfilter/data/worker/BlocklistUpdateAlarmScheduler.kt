package com.deatrg.dnsfilter.data.worker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Blocklist 更新调度器（AlarmManager 原生实现）
 * 负责安排每天自动更新 blocklist 的定时任务
 */
class BlocklistUpdateAlarmScheduler(private val context: Context) {

    companion object {
        private const val TAG = "BlocklistUpdateAlarmScheduler"
        private const val REQUEST_CODE_DAILY = 1001
        private const val REQUEST_CODE_IMMEDIATE = 1002
    }

    private val alarmManager: AlarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /**
     * 调度每天自动更新任务（当地时间 12:00）
     */
    fun scheduleDailyUpdate() {
        Log.d(TAG, "Scheduling daily blocklist update alarm at 12:00")

        val triggerAtMillis = calculateNextNoon()
        val pendingIntent = createDailyPendingIntent()

        // 先取消已存在的 alarm，避免重复
        alarmManager.cancel(pendingIntent)

        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMillis,
                        pendingIntent
                    )
                    Log.d(TAG, "Exact alarm scheduled at $triggerAtMillis")
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMillis,
                        pendingIntent
                    )
                    Log.d(TAG, "Inexact alarm scheduled at $triggerAtMillis (exact alarm permission not granted)")
                }
            }

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
                Log.d(TAG, "Exact alarm scheduled at $triggerAtMillis")
            }

            else -> {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
                Log.d(TAG, "Exact alarm scheduled at $triggerAtMillis")
            }
        }
    }

    /**
     * 取消每日更新任务
     */
    fun cancelDailyUpdate() {
        Log.d(TAG, "Cancelling daily blocklist update alarm")
        alarmManager.cancel(createDailyPendingIntent())
    }

    /**
     * 手动触发一次立即更新（延迟 1 秒确保 alarm 能注册成功）
     */
    fun triggerImmediateUpdate() {
        Log.d(TAG, "Triggering immediate blocklist update alarm")

        val triggerAtMillis = System.currentTimeMillis() + 1000L
        val intent = Intent(context, BlocklistUpdateAlarmReceiver::class.java).apply {
            action = BlocklistUpdateAlarmReceiver.ACTION_UPDATE_BLOCKLIST
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_IMMEDIATE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms() -> {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }

            else -> {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }
        }
    }

    private fun createDailyPendingIntent(): PendingIntent {
        val intent = Intent(context, BlocklistUpdateAlarmReceiver::class.java).apply {
            action = BlocklistUpdateAlarmReceiver.ACTION_UPDATE_BLOCKLIST
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_DAILY,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 计算下一个当地时间 12:00 的时间戳
     */
    private fun calculateNextNoon(): Long {
        val calendar = java.util.Calendar.getInstance()
        val now = calendar.timeInMillis

        calendar.set(java.util.Calendar.HOUR_OF_DAY, 12)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)

        if (now >= calendar.timeInMillis) {
            calendar.add(java.util.Calendar.DAY_OF_YEAR, 1)
        }

        return calendar.timeInMillis
    }
}
