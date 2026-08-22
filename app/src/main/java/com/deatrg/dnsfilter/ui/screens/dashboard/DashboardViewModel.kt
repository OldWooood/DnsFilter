package com.deatrg.dnsfilter.ui.screens.dashboard

import android.app.Application
import android.content.Intent
import android.net.VpnService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.deatrg.dnsfilter.AppLog
import com.deatrg.dnsfilter.ServiceLocator
import com.deatrg.dnsfilter.data.local.StatisticsBuffer
import com.deatrg.dnsfilter.domain.model.DnsStatistics
import com.deatrg.dnsfilter.domain.repository.DnsServerRepository
import com.deatrg.dnsfilter.domain.repository.FilterListRepository
import com.deatrg.dnsfilter.service.DnsVpnService
import com.deatrg.dnsfilter.service.VpnStateHolder
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** toggleVpn 失败时向 UI 报告的错误类型。 */
enum class VpnError {
    /** 没有启用的 DNS 服务器 */
    NoDnsServers,

    /** blocklist 数据不可用（下载失败且无缓存） */
    NoBlocklistData,

    /** VPN 启动失败或超时 */
    StartFailed
}

class DashboardViewModel(
    application: Application,
    private val dnsServerRepository: DnsServerRepository,
    private val filterListRepository: FilterListRepository,
    private val statisticsBuffer: StatisticsBuffer,
    private val vpnState: VpnStateHolder
) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "DashboardViewModel"
        private const val VPN_STATE_TIMEOUT_MS = 5000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                DashboardViewModel(
                    application = app,
                    dnsServerRepository = ServiceLocator.provideDnsServerRepository(),
                    filterListRepository = ServiceLocator.provideFilterListRepository(),
                    statisticsBuffer = ServiceLocator.provideStatisticsBuffer(),
                    vpnState = ServiceLocator.provideVpnStateHolder()
                )
            }
        }
    }

    // VPN实际运行状态（由 DnsVpnService 写入 VpnStateHolder，UI 直接订阅）
    val isVpnRunning: StateFlow<Boolean> = vpnState.isRunning

    // VPN操作是否正在处理中
    private val _isVpnProcessing = MutableStateFlow(false)
    val isVpnProcessing: StateFlow<Boolean> = _isVpnProcessing.asStateFlow()

    // toggleVpn 的错误事件
    private val _vpnErrors = MutableSharedFlow<VpnError>(extraBufferCapacity = 1)
    val vpnErrors: SharedFlow<VpnError> = _vpnErrors.asSharedFlow()

    // Blocklist 状态
    val isFilterLoaded = filterListRepository.isLoaded
    val isFilterLoading = filterListRepository.isLoading
    val filterListCount = filterListRepository.filterListCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val downloadProgress = filterListRepository.downloadProgress

    // 使用内存缓冲的统计信息
    val statistics: StateFlow<DnsStatistics> = statisticsBuffer.statistics
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DnsStatistics())

    init {
        // 服务侧上报的启动失败事件转发给 UI
        viewModelScope.launch {
            vpnState.events.collect { event ->
                if (event is VpnStateHolder.Event.StartFailed) {
                    _vpnErrors.tryEmit(VpnError.StartFailed)
                }
            }
        }

        // 初始化：从本地缓存加载 blocklist（不下载）
        viewModelScope.launch {
            runCatching { filterListRepository.loadFilterLists() }
                .onFailure { AppLog.e(TAG, "Failed to load filter lists", it) }
        }
    }

    /**
     * 切换VPN状态
     */
    fun toggleVpn(targetEnabled: Boolean) {
        viewModelScope.launch {
            AppLog.d(TAG) { "toggleVpn: target=$targetEnabled, current=${vpnState.isRunning.value}" }
            val appContext = getApplication<Application>()

            if (vpnState.isRunning.value == targetEnabled) {
                return@launch
            }

            _isVpnProcessing.value = true

            try {
                if (targetEnabled) {
                    // 1. 检查是否有启用的 DNS 服务器
                    val enabledCount = dnsServerRepository.dnsServers.first().count { it.isEnabled }
                    if (enabledCount == 0) {
                        _vpnErrors.tryEmit(VpnError.NoDnsServers)
                        return@launch
                    }

                    // 2. 确保 blocklist 已加载（从缓存或下载）
                    val hasData = ensureBlocklistsReady()
                    if (!hasData) {
                        AppLog.e(TAG, "No blocklist data available, cannot start VPN")
                        _vpnErrors.tryEmit(VpnError.NoBlocklistData)
                        return@launch
                    }

                    // 3. 启动 VPN 并等待状态就绪
                    appContext.startForegroundService(startIntent(appContext))
                    if (!waitForVpnState(true)) {
                        AppLog.e(TAG, "VPN start timeout")
                        _vpnErrors.tryEmit(VpnError.StartFailed)
                    } else {
                        AppLog.d(TAG, "VPN started successfully")
                    }
                } else {
                    appContext.startService(stopIntent(appContext))
                    if (!waitForVpnState(false)) {
                        AppLog.e(TAG, "VPN stop timeout")
                    } else {
                        AppLog.d(TAG, "VPN stopped successfully")
                    }
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "Error toggling VPN", e)
            } finally {
                _isVpnProcessing.value = false
            }
        }
    }

    /**
     * 确保 blocklist 已就绪
     * @return true 如果有可用数据（从缓存加载成功或下载成功）
     */
    private suspend fun ensureBlocklistsReady(): Boolean {
        val alreadyLoaded = filterListRepository.isLoaded.first() &&
                filterListRepository.filterListCount.first() > 0
        if (alreadyLoaded) return true

        AppLog.d(TAG, "Loading blocklists...")
        return filterListRepository.loadFilterLists()
    }

    /**
     * 等待 VPN 状态变为目标值（订阅状态流，无轮询）
     */
    private suspend fun waitForVpnState(targetState: Boolean): Boolean {
        return withTimeoutOrNull(VPN_STATE_TIMEOUT_MS) {
            vpnState.isRunning.first { it == targetState }
        } != null
    }

    fun requestVpnPermission(): Intent? {
        return VpnService.prepare(getApplication())
    }

    private fun startIntent(context: Application): Intent =
        Intent(context, DnsVpnService::class.java).apply { action = DnsVpnService.ACTION_START }

    private fun stopIntent(context: Application): Intent =
        Intent(context, DnsVpnService::class.java).apply { action = DnsVpnService.ACTION_STOP }
}
