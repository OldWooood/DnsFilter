package com.deatrg.dnsfilter

import android.app.Application
import com.deatrg.dnsfilter.data.worker.BlocklistUpdateAlarmScheduler
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

        // 使用 AlarmManager 每天 12:00 自动更新 blocklist
        try {
            val scheduler = BlocklistUpdateAlarmScheduler(this)
            scheduler.scheduleDailyUpdate()
        } catch (e: Exception) {
            AppLog.e("DnsFilterApplication", "Failed to schedule blocklist update", e)
        }
    }
}
