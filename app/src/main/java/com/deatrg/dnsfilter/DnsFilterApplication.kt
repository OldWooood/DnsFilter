package com.deatrg.dnsfilter

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.deatrg.dnsfilter.data.worker.BlocklistUpdateWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class DnsFilterApplication : Application() {

    companion object {
        private const val BLOCKLIST_UPDATE_WORK = "blocklist_update_work"
    }

    private val applicationScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)

        // 初始化默认 DNS 服务器和过滤列表（首次安装时）
        applicationScope.launch {
            val prefs = ServiceLocator.providePreferencesManager()
            prefs.ensureDefaultServersInitialized()
            prefs.ensureDefaultFilterListsInitialized()
        }

        // 使用 WorkManager 每天自动更新 blocklist
        try {
            scheduleBlocklistUpdate()
        } catch (e: Exception) {
            AppLog.e("DnsFilterApplication", "Failed to schedule blocklist update", e)
        }
    }

    private fun scheduleBlocklistUpdate() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val updateRequest = PeriodicWorkRequestBuilder<BlocklistUpdateWorker>(
            24, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                10, TimeUnit.MINUTES
            )
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            BLOCKLIST_UPDATE_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            updateRequest
        )
    }
}
