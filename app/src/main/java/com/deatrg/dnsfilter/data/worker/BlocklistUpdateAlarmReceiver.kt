package com.deatrg.dnsfilter.data.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.deatrg.dnsfilter.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * AlarmManager 触发的 Blocklist 更新接收器
 * 同时处理 BOOT_COMPLETED 重新调度 alarm
 */
class BlocklistUpdateAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "BlocklistUpdateAlarmReceiver"
        const val ACTION_UPDATE_BLOCKLIST = "com.deatrg.dnsfilter.ACTION_UPDATE_BLOCKLIST"
        private const val WAKE_LOCK_TAG = "DnsFilter::BlocklistUpdateWakeLock"
        private const val WAKE_LOCK_TIMEOUT_MS = 60_000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.d(TAG, "Boot completed, rescheduling blocklist update alarm")
                BlocklistUpdateAlarmScheduler(context).scheduleDailyUpdate()
            }

            ACTION_UPDATE_BLOCKLIST -> {
                Log.d(TAG, "Alarm fired, starting blocklist update")
                val pendingResult = goAsync()

                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                val wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    WAKE_LOCK_TAG
                ).apply {
                    acquire(WAKE_LOCK_TIMEOUT_MS)
                }

                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val repository = ServiceLocator.provideFilterListRepository()

                        // 先加载本地保存的 filter 列表到 DomainFilter
                        repository.loadFilterLists()

                        // 检查并更新过期的 blocklist
                        repository.checkAndUpdate()

                        Log.d(TAG, "Blocklist update completed successfully")
                    } catch (e: Exception) {
                        Log.e(TAG, "Blocklist update failed", e)
                    } finally {
                        // 调度次日 alarm
                        try {
                            BlocklistUpdateAlarmScheduler(context).scheduleDailyUpdate()
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to reschedule next alarm", e)
                        }

                        if (wakeLock.isHeld) {
                            wakeLock.release()
                        }
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}
