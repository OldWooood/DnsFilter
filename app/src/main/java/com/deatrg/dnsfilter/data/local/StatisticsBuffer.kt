package com.deatrg.dnsfilter.data.local

import com.deatrg.dnsfilter.domain.model.DnsStatistics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory statistics for the current app process. Values reset when the app restarts.
 *
 * 计数实时累加，UI 流最多每 [UI_UPDATE_INTERVAL_MS] 发布一次快照以减少重组。
 * 调度只依赖协程 delay（monotonic），不受系统时间修改影响。
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

    private val publishScheduled = AtomicBoolean(false)

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

        schedulePublish()
    }

    fun reset() {
        _totalQueries.set(0)
        _blockedQueries.set(0)
        _allowedQueries.set(0)
        _totalResponseTime.set(0)
        _queryCount.set(0)
        publishSnapshot()
    }

    private fun schedulePublish() {
        if (!publishScheduled.compareAndSet(false, true)) return
        scope.launch {
            try {
                delay(UI_UPDATE_INTERVAL_MS)
            } finally {
                publishScheduled.set(false)
            }
            publishSnapshot()
        }
    }

    private fun publishSnapshot() {
        _statistics.value = DnsStatistics(
            totalQueries = _totalQueries.get(),
            blockedQueries = _blockedQueries.get(),
            allowedQueries = _allowedQueries.get(),
            averageResponseTime = if (_queryCount.get() > 0) {
                _totalResponseTime.get() / _queryCount.get()
            } else 0
        )
    }
}
