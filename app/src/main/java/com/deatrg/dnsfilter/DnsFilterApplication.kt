package com.deatrg.dnsfilter

import android.app.Application
import androidx.work.WorkManager
import com.deatrg.dnsfilter.data.worker.BlocklistUpdateAlarmScheduler
import com.deatrg.dnsfilter.data.worker.BlocklistUpdateWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DnsFilterApplication : Application() {

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

        // 取消遗留的 WorkManager 任务，避免干扰
        WorkManager.getInstance(this).cancelUniqueWork(BlocklistUpdateWorker.WORK_NAME)

        // 使用 AlarmManager 调度每天自动更新 blocklist（比 WorkManager 更可靠）
        val scheduler = BlocklistUpdateAlarmScheduler(this)
        scheduler.scheduleDailyUpdate()
    }
}
