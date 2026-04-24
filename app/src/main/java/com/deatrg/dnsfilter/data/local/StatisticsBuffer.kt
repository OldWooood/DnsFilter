package com.deatrg.dnsfilter.data.local

import com.deatrg.dnsfilter.domain.model.DnsStatistics
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 内存中的统计信息缓冲区，减少磁盘 I/O
 */
class StatisticsBuffer(
    private val preferencesManager: PreferencesManager,
    private val scope: CoroutineScope
) {
    private val _totalQueries = AtomicLong(0)
    private val _blockedQueries = AtomicLong(0)
    private val _allowedQueries = AtomicLong(0)
    private val _totalResponseTime = AtomicLong(0)
    private val _queryCount = AtomicLong(0)

    private val _statistics = MutableStateFlow(DnsStatistics())
    val statistics: StateFlow<DnsStatistics> = _statistics.asStateFlow()

    private var flushJob: Job? = null
    private val lastUpdateTime = AtomicLong(0)
    private val isInitialized = AtomicBoolean(false)

    companion object {
        private const val FLUSH_INTERVAL_MS = 5000L // 5秒批量写入一次
        private const val UI_UPDATE_INTERVAL_MS = 1000L // 1秒节流更新 UI，避免频繁重组
    }

    /**
     * 从持久化存储加载初始值
     */
    suspend fun loadInitialValues() {
        if (!isInitialized.compareAndSet(false, true)) {
            updateFlow()
            return
        }

        val state = preferencesManager.getStatisticsState()
        _totalQueries.set(state.statistics.totalQueries)
        _blockedQueries.set(state.statistics.blockedQueries)
        _allowedQueries.set(state.statistics.allowedQueries)
        _totalResponseTime.set(state.totalResponseTime)
        _queryCount.set(state.responseSampleCount)
        updateFlow()
    }

    /**
     * 记录一次查询（内存操作，无磁盘 I/O）
     */
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

        updateFlow()

        // 启动定时 flush
        ensureFlushScheduled()
    }

    /**
     * 立即刷新到磁盘
     */
    suspend fun flush() {
        val total = _totalQueries.get()
        val blocked = _blockedQueries.get()
        val allowed = _allowedQueries.get()
        val avgResponse = if (_queryCount.get() > 0) {
            _totalResponseTime.get() / _queryCount.get()
        } else 0

        preferencesManager.updateStatistics(
            DnsStatistics(
                totalQueries = total,
                blockedQueries = blocked,
                allowedQueries = allowed,
                averageResponseTime = avgResponse
            ),
            totalResponseTime = _totalResponseTime.get(),
            responseSampleCount = _queryCount.get()
        )
    }

    /**
     * 重置统计
     */
    suspend fun reset() {
        _totalQueries.set(0)
        _blockedQueries.set(0)
        _allowedQueries.set(0)
        _totalResponseTime.set(0)
        _queryCount.set(0)
        updateFlow()
        preferencesManager.resetStatistics()
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

    private fun ensureFlushScheduled() {
        if (flushJob?.isActive != true) {
            flushJob = scope.launch {
                delay(FLUSH_INTERVAL_MS)
                flush()
            }
        }
    }
}
