package com.deatrg.dnsfilter.data.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.deatrg.dnsfilter.ServiceLocator

/**
 * Blocklist 后台更新 Worker
 * 使用 WorkManager 实现每天自动更新
 */
class BlocklistUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "BlocklistUpdateWorker"
        const val WORK_NAME = "blocklist_daily_update"
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting daily blocklist update")

        return try {
            val repository = ServiceLocator.provideFilterListRepository()

            // 检查并更新过期的 blocklist
            repository.checkAndUpdate()

            Log.d(TAG, "Daily blocklist update completed successfully")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Daily blocklist update failed", e)
            // 网络错误等临时性问题，重试
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
}