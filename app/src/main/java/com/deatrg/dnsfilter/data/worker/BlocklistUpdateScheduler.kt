package com.deatrg.dnsfilter.data.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * Blocklist 更新调度器
 * 负责安排每天自动更新 blocklist 的 WorkManager 任务
 */
class BlocklistUpdateScheduler(private val context: Context) {

    companion object {
        private const val TAG = "BlocklistUpdateScheduler"
        private const val UPDATE_INTERVAL_HOURS = 24L
    }

    /**
     * 调度每天自动更新任务
     * 使用 PeriodicWorkRequest 确保任务每天执行一次
     */
    fun scheduleDailyUpdate() {
        Log.d(TAG, "Scheduling daily blocklist update")

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED) // 需要网络连接
            .setRequiresBatteryNotLow(true) // 电量不低
            .build()

        // 创建每日重复的工作请求
        // flex 设为 15 分钟，使执行窗口更贴近目标时间
        val dailyUpdateRequest = PeriodicWorkRequestBuilder<BlocklistUpdateWorker>(
            UPDATE_INTERVAL_HOURS, TimeUnit.HOURS,
            15, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setInitialDelay(calculateInitialDelay(), TimeUnit.MILLISECONDS)
            .addTag(BlocklistUpdateWorker.WORK_NAME)
            .build()

        // 使用 UPDATE 策略替换旧任务，确保代码修复后能生效
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            BlocklistUpdateWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            dailyUpdateRequest
        )

        Log.d(TAG, "Daily blocklist update scheduled successfully")
    }

    /**
     * 取消每日更新任务
     */
    fun cancelDailyUpdate() {
        Log.d(TAG, "Cancelling daily blocklist update")
        WorkManager.getInstance(context).cancelUniqueWork(BlocklistUpdateWorker.WORK_NAME)
    }

    /**
     * 手动触发一次立即更新
     */
    fun triggerImmediateUpdate() {
        Log.d(TAG, "Triggering immediate blocklist update")

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val immediateRequest = OneTimeWorkRequestBuilder<BlocklistUpdateWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueue(immediateRequest)
    }

    /**
     * 计算初始延迟，使更新任务在当地时间 12:00 执行
     * 如果当前时间在 12:00 之前，则在今天 12:00 执行
     * 否则在明天 12:00 执行
     */
    private fun calculateInitialDelay(): Long {
        val calendar = java.util.Calendar.getInstance()
        val now = calendar.timeInMillis

        calendar.set(java.util.Calendar.HOUR_OF_DAY, 12)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)

        // 如果已过 12:00，安排到明天
        if (now >= calendar.timeInMillis) {
            calendar.add(java.util.Calendar.DAY_OF_YEAR, 1)
        }

        return calendar.timeInMillis - now
    }
}