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
        val dailyUpdateRequest = PeriodicWorkRequestBuilder<BlocklistUpdateWorker>(
            UPDATE_INTERVAL_HOURS, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .setInitialDelay(calculateInitialDelay(), TimeUnit.MILLISECONDS)
            .addTag(BlocklistUpdateWorker.WORK_NAME)
            .build()

        // 将工作加入队列，使用 REPLACE 策略确保只有一个更新任务
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            BlocklistUpdateWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
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
     * 计算初始延迟，使更新任务在凌晨 4 点执行
     * 如果当前时间在 4 点之前，则在今天 4 点执行
     * 否则在明天 4 点执行
     */
    private fun calculateInitialDelay(): Long {
        val calendar = java.util.Calendar.getInstance()
        val now = calendar.timeInMillis

        calendar.set(java.util.Calendar.HOUR_OF_DAY, 4)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)

        // 如果已过 4 点，安排到明天
        if (now >= calendar.timeInMillis) {
            calendar.add(java.util.Calendar.DAY_OF_YEAR, 1)
        }

        return calendar.timeInMillis - now
    }
}