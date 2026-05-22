package com.deatrg.dnsfilter.data.worker

import com.deatrg.dnsfilter.AppLog
import com.deatrg.dnsfilter.ServiceLocator
import java.util.concurrent.atomic.AtomicBoolean

object BlocklistUpdateRunner {
    private const val TAG = "BlocklistUpdateRunner"
    private val isUpdating = AtomicBoolean(false)

    suspend fun run(): Boolean {
        if (!isUpdating.compareAndSet(false, true)) {
            AppLog.d(TAG, "Blocklist update already running")
            return false
        }

        try {
            return runInternal()
        } finally {
            isUpdating.set(false)
        }
    }

    private suspend fun runInternal(): Boolean {
        val preferencesManager = ServiceLocator.providePreferencesManager()
        preferencesManager.ensureDefaultFilterListsInitialized()

        val repository = ServiceLocator.provideFilterListRepository()
        repository.loadFilterLists()
        return repository.checkAndUpdate()
    }
}
