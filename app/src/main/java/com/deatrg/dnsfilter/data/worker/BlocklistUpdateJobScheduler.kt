package com.deatrg.dnsfilter.data.worker

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import com.deatrg.dnsfilter.AppLog

class BlocklistUpdateJobScheduler(
    private val context: Context
) {
    companion object {
        private const val TAG = "BlocklistUpdateJobScheduler"
        private const val JOB_ID = 2001
    }

    fun scheduleImmediateUpdate() {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val componentName = ComponentName(context, BlocklistUpdateJobService::class.java)
        val jobInfo = JobInfo.Builder(JOB_ID, componentName)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setOverrideDeadline(0)
            .build()

        val result = scheduler.schedule(jobInfo)
        AppLog.d(TAG, "Scheduled immediate blocklist update job, result=$result")
    }
}
