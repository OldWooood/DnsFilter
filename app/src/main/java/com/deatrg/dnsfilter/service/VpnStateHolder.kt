package com.deatrg.dnsfilter.service

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * VPN 运行状态的单一事实来源。
 * DnsVpnService 负责写入；UI 与等待逻辑通过 collect 订阅，避免轮询静态变量。
 */
class VpnStateHolder {

    /** 一次性 UI 事件。 */
    sealed interface Event {
        /** VPN 启动失败（权限被撤销、establish 失败等）。 */
        data object StartFailed : Event
    }

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 1)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    fun setRunning(running: Boolean) {
        _isRunning.value = running
    }

    fun reportStartFailed() {
        _events.tryEmit(Event.StartFailed)
    }
}
