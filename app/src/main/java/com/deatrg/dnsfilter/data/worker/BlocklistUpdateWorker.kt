package com.deatrg.dnsfilter.data.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.deatrg.dnsfilter.AppLog
import com.deatrg.dnsfilter.ServiceLocator

class BlocklistUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "BlocklistUpdateWorker"
    }

    override suspend fun doWork(): Result {
        AppLog.d(TAG, "WorkManager triggered blocklist update")
        return try {
            val repository = ServiceLocator.provideFilterListRepository()
            repository.loadFilterLists()
            repository.checkAndUpdate()
            AppLog.d(TAG, "Blocklist update completed successfully")
            Result.success()
        } catch (e: Exception) {
            AppLog.e(TAG, "Blocklist update failed", e)
            Result.retry()
        }
    }
}
