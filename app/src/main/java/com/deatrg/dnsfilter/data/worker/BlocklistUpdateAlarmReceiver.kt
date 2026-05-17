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
import kotlinx.coroutines.withTimeout

class BlocklistUpdateAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BlocklistUpdateAlarmReceiver"
        private const val WAKE_LOCK_TIMEOUT_MS = 60000L
        private const val UPDATE_TIMEOUT_MS = 55000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                AppLog.d(TAG, "Boot completed, rescheduling daily update")
                val scheduler = BlocklistUpdateAlarmScheduler(context)
                scheduler.scheduleDailyUpdate()
            }
            BlocklistUpdateAlarmScheduler.ACTION_UPDATE_BLOCKLIST -> {
                AppLog.d(TAG, "Alarm triggered: starting blocklist update")
                val pendingResult = goAsync()
                val wakeLock = acquireWakeLock(context)

                scope.launch {
                    try {
                        withTimeout(UPDATE_TIMEOUT_MS) {
                            val repository = ServiceLocator.provideFilterListRepository()
                            repository.loadFilterLists()
                            repository.checkAndUpdate()
                            AppLog.d(TAG, "Blocklist update completed successfully")
                        }
                    } catch (e: Exception) {
                        AppLog.e(TAG, "Blocklist update failed", e)
                    } finally {
                        val scheduler = BlocklistUpdateAlarmScheduler(context)
                        scheduler.scheduleDailyUpdate()

                        releaseWakeLock(wakeLock)
                        pendingResult.finish()
                    }
                }
            }
        }
    }

    private fun acquireWakeLock(context: Context): PowerManager.WakeLock? {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "DnsFilter::BlocklistUpdateWakeLock"
        )
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
        return wakeLock
    }

    private fun releaseWakeLock(wakeLock: PowerManager.WakeLock?) {
        try {
            wakeLock?.release()
        } catch (e: Exception) {
            AppLog.e(TAG, "Error releasing wake lock", e)
        }
    }
}
