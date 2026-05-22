package com.deatrg.dnsfilter.data.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.deatrg.dnsfilter.AppLog

class BlocklistUpdateAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BlocklistUpdateAlarmReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            BlocklistUpdateAlarmScheduler.ACTION_UPDATE_BLOCKLIST -> {
                AppLog.d(TAG, "Alarm triggered, starting update service")
                val scheduler = BlocklistUpdateAlarmScheduler(context)
                scheduler.scheduleDailyUpdate()
                val serviceStarted = BlocklistUpdateService.start(context)
                if (!serviceStarted) {
                    BlocklistUpdateJobScheduler(context).scheduleImmediateUpdate()
                }
            }
        }
    }
}
