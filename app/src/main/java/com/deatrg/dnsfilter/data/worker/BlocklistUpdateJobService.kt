package com.deatrg.dnsfilter.data.worker

import android.app.job.JobParameters
import android.app.job.JobService
import com.deatrg.dnsfilter.AppLog
import com.deatrg.dnsfilter.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class BlocklistUpdateJobService : JobService() {

    companion object {
        private const val TAG = "BlocklistUpdateJobService"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onStartJob(params: JobParameters): Boolean {
        ServiceLocator.init(applicationContext)

        scope.launch {
            try {
                AppLog.d(TAG, "Starting blocklist update job")
                val updated = BlocklistUpdateRunner.run()
                AppLog.d(TAG, "Blocklist update job completed, updated=$updated")
                jobFinished(params, false)
            } catch (e: Exception) {
                AppLog.e(TAG, "Blocklist update job failed", e)
                jobFinished(params, true)
            }
        }

        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
