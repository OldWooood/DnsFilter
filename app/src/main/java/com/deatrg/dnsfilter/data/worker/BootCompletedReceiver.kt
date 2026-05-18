package com.deatrg.dnsfilter.data.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.deatrg.dnsfilter.AppLog

class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootCompletedReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        AppLog.d(TAG, "Boot completed, rescheduling daily update")
        val scheduler = BlocklistUpdateAlarmScheduler(context)
        scheduler.scheduleDailyUpdate()
    }
}
