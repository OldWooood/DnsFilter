package com.deatrg.dnsfilter.data.worker

import android.content.Context
import com.deatrg.dnsfilter.AppLog
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.deatrg.dnsfilter.ServiceLocator

/**
 * Blocklist 后台更新 Worker
 * 使用 WorkManager 实现每天自动更新
 *
 * @deprecated 已改用 AlarmManager + BroadcastReceiver 实现，避免国产 OEM 杀死 WorkManager 任务
 */
@Deprecated("已改用 AlarmManager 实现")
class BlocklistUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "BlocklistUpdateWorker"
        const val WORK_NAME = "blocklist_daily_update"
    }

    override suspend fun doWork(): Result {
        AppLog.d(TAG, "Starting daily blocklist update")

        return try {
            val repository = ServiceLocator.provideFilterListRepository()

            // 先加载本地保存的 filter 列表到 DomainFilter，否则 filterListsToLoad 为空
            repository.loadFilterLists()

            // 检查并更新过期的 blocklist
            repository.checkAndUpdate()

            AppLog.d(TAG, "Daily blocklist update completed successfully")
            Result.success()
        } catch (e: Exception) {
            AppLog.e(TAG, "Daily blocklist update failed", e)
            // 网络错误等临时性问题，重试
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
}