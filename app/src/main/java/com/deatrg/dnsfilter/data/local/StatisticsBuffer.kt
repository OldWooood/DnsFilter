package com.deatrg.dnsfilter.data.local

import com.deatrg.dnsfilter.domain.model.DnsStatistics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory statistics for the current app process. Values reset when the app restarts.
 */
class StatisticsBuffer(
    private val scope: CoroutineScope
) {
    private val _totalQueries = AtomicLong(0)
    private val _blockedQueries = AtomicLong(0)
    private val _allowedQueries = AtomicLong(0)
    private val _totalResponseTime = AtomicLong(0)
    private val _queryCount = AtomicLong(0)

    private val _statistics = MutableStateFlow(DnsStatistics())
    val statistics: StateFlow<DnsStatistics> = _statistics.asStateFlow()

    private var uiUpdateJob: Job? = null
    private val lastUpdateTime = AtomicLong(0)

    companion object {
        private const val UI_UPDATE_INTERVAL_MS = 1000L
    }

    fun recordQuery(blocked: Boolean, responseTime: Long, includeInAvg: Boolean = true) {
        _totalQueries.incrementAndGet()
        if (blocked) {
            _blockedQueries.incrementAndGet()
        } else {
            _allowedQueries.incrementAndGet()
        }
        if (includeInAvg) {
            _totalResponseTime.addAndGet(responseTime)
            _queryCount.incrementAndGet()
        }

        ensureUiUpdateScheduled()
    }

    fun reset() {
        _totalQueries.set(0)
        _blockedQueries.set(0)
        _allowedQueries.set(0)
        _totalResponseTime.set(0)
        _queryCount.set(0)
        lastUpdateTime.set(0)
        updateFlow()
    }

    private fun updateFlow() {
        val now = System.currentTimeMillis()
        val last = lastUpdateTime.get()
        if (now - last < UI_UPDATE_INTERVAL_MS) return
        if (!lastUpdateTime.compareAndSet(last, now)) return

        val total = _totalQueries.get()
        val blocked = _blockedQueries.get()
        val allowed = _allowedQueries.get()
        val avgResponse = if (_queryCount.get() > 0) {
            _totalResponseTime.get() / _queryCount.get()
        } else 0

        _statistics.value = DnsStatistics(
            totalQueries = total,
            blockedQueries = blocked,
            allowedQueries = allowed,
            averageResponseTime = avgResponse
        )
    }

    private fun ensureUiUpdateScheduled() {
        if (uiUpdateJob?.isActive != true) {
            uiUpdateJob = scope.launch {
                delay(UI_UPDATE_INTERVAL_MS)
                updateFlow()
            }
        }
    }
}
