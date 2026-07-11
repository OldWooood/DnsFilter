package com.deatrg.dnsfilter.data.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.deatrg.dnsfilter.AppLog
import com.deatrg.dnsfilter.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BlocklistUpdateAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BlocklistUpdate"
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val scheduler = BlocklistUpdateAlarmScheduler(appContext)

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> scheduler.scheduleNext()

            BlocklistUpdateAlarmScheduler.ACTION_UPDATE_BLOCKLISTS -> {
                // Schedule first so a process kill during a long download cannot lose the
                // following day's alarm.
                scheduler.scheduleNext()
                val pendingResult = goAsync()
                val powerManager = appContext.getSystemService(PowerManager::class.java)
                val wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "DnsFilter:blocklist-update"
                ).apply {
                    acquire(WAKE_LOCK_TIMEOUT_MS)
                }

                CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                    try {
                        ServiceLocator.init(appContext)
                        val domainFilter = ServiceLocator.provideDomainFilter()
                        if (domainFilter.isLoading.value) {
                            AppLog.d(TAG, "Blocklist refresh already running; automatic update skipped")
                        } else {
                            ServiceLocator.provideFilterListRepository().refreshLists()
                            AppLog.d(TAG, "Automatic blocklist refresh completed")
                        }
                    } catch (e: Exception) {
                        // DomainFilter retains old cache data when a download fails.
                        AppLog.e(TAG, "Automatic blocklist refresh failed", e)
                    } finally {
                        if (wakeLock.isHeld) wakeLock.release()
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}
