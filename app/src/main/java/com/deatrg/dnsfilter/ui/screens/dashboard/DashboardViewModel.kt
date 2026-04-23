package com.deatrg.dnsfilter.ui.screens.dashboard

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import com.deatrg.dnsfilter.AppLog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.deatrg.dnsfilter.ServiceLocator
import com.deatrg.dnsfilter.data.local.PreferencesManager
import com.deatrg.dnsfilter.data.local.StatisticsBuffer
import com.deatrg.dnsfilter.data.remote.DomainFilter
import com.deatrg.dnsfilter.data.repository.FilterListRepositoryImpl
import com.deatrg.dnsfilter.domain.model.DnsStatistics
import com.deatrg.dnsfilter.service.DnsVpnService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DashboardViewModel(
    private val context: Context,
    private val preferencesManager: PreferencesManager,
    private val statisticsBuffer: StatisticsBuffer,
    private val filterListRepository: FilterListRepositoryImpl,
    private val domainFilter: DomainFilter
) : ViewModel() {
    companion object {
        private const val TAG = "DashboardViewModel"
        private const val VPN_STATE_TIMEOUT_MS = 5000L
        private const val POLL_INTERVAL_MS = 1000L
    }

    // VPN实际运行状态（从Service读取）
    private val _isVpnActuallyRunning = MutableStateFlow(false)
    val isVpnActuallyRunning: StateFlow<Boolean> = _isVpnActuallyRunning.asStateFlow()

    // VPN操作是否正在处理中
    private val _isVpnProcessing = MutableStateFlow(false)
    val isVpnProcessing: StateFlow<Boolean> = _isVpnProcessing.asStateFlow()

    // 显示无 DNS 服务器错误的 snackbar
    private val _showNoDnsServersError = MutableSharedFlow<Unit>()
    val showNoDnsServersError: SharedFlow<Unit> = _showNoDnsServersError.asSharedFlow()

    // 是否暂停轮询（应用在后台时暂停，省电）
    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    // Blocklist 状态
    val isFilterLoaded = domainFilter.isLoaded
    val isFilterLoading = domainFilter.isLoading
    val filterListCount = domainFilter.filterListCount
    val downloadProgress = domainFilter.downloadProgress
    val enabledDnsServerCount = filterListRepository.enabledDnsServerCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // 使用内存缓冲的统计信息
    val statistics: StateFlow<DnsStatistics> = statisticsBuffer.statistics
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DnsStatistics())

    init {
        // 定期检查VPN实际运行状态并同步到UI（使用 Flow 实现真正暂停，彻底消除后台高频唤醒）
        viewModelScope.launch {
            _isPaused
                .flatMapLatest { paused ->
                    if (paused) {
                        // 完全停止发射，协程无限挂起，不消耗 CPU
                        flow { delay(Long.MAX_VALUE) }
                    } else {
                        flow {
                            while (true) {
                                emit(Unit)
                                delay(POLL_INTERVAL_MS)
                            }
                        }
                    }
                }
                .collect {
                    updateVpnRunningState()
                }
        }

        // 加载统计信息初始值
        viewModelScope.launch {
            statisticsBuffer.loadInitialValues()
        }

        // 初始化：从本地缓存加载 blocklist（不下载）
        // 注意：不在这里调用 checkForUpdates()，由 AlarmManager 负责定时更新
        viewModelScope.launch {
            filterListRepository.loadFilterLists()
        }
    }

    /**
     * 更新VPN运行状态
     */
    private fun updateVpnRunningState() {
        _isVpnActuallyRunning.value = DnsVpnService.isServiceRunning
    }

    /**
     * 切换VPN状态
     */
    fun toggleVpn(targetEnabled: Boolean) {
        viewModelScope.launch {
            AppLog.d(TAG, "toggleVpn: target=$targetEnabled, current=${_isVpnActuallyRunning.value}")
            
            if (_isVpnActuallyRunning.value == targetEnabled) {
                return@launch
            }
            
            _isVpnProcessing.value = true
            
            try {
                if (targetEnabled) {
                    // 1. 检查是否有启用的 DNS 服务器（直接从 preferences 读取当前值）
                    val enabledCount = preferencesManager.dnsServers.first().count { it.isEnabled }
                    if (enabledCount == 0) {
                        _showNoDnsServersError.emit(Unit)
                        return@launch
                    }

                    // 2. 确保 blocklist 已加载（从缓存或下载）
                    val hasData = ensureFilterListsLoaded()
                    
                    if (!hasData) {
                        AppLog.e(TAG, "No filter lists available, cannot start VPN")
                        // 可以在这里显示错误提示
                        return@launch
                    }

                    // 2. 启动 VPN
                    val intent = Intent(context, DnsVpnService::class.java).apply {
                        action = DnsVpnService.ACTION_START
                    }
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }

                    // 等待Service状态变为目标状态
                    val success = waitForVpnState(true)
                    
                    if (success) {
                        preferencesManager.setVpnEnabled(true)
                        AppLog.d(TAG, "VPN started successfully")
                    } else {
                        AppLog.e(TAG, "VPN start timeout")
                    }
                } else {
                    // 停止 VPN
                    val intent = Intent(context, DnsVpnService::class.java).apply {
                        action = DnsVpnService.ACTION_STOP
                    }
                    context.startService(intent)
                    
                    val success = waitForVpnState(false)
                    
                    if (success) {
                        preferencesManager.setVpnEnabled(false)
                        // 刷新统计信息到磁盘
                        statisticsBuffer.flush()
                        AppLog.d(TAG, "VPN stopped successfully")
                    } else {
                        AppLog.e(TAG, "VPN stop timeout")
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
     * 确保过滤列表已加载
     * @return true 如果有可用数据（从缓存加载成功或下载成功）
     */
    private suspend fun ensureFilterListsLoaded(): Boolean {
        // 如果已经加载且有数据，直接返回
        if (domainFilter.isLoaded.value && domainFilter.filterListCount.value > 0) {
            AppLog.d(TAG, "Filter lists already loaded: ${domainFilter.filterListCount.value} domains")
            return true
        }

        // 需要加载（从缓存或下载）
        AppLog.d(TAG, "Loading filter lists...")
        val loaded = domainFilter.loadFilterLists()

        return loaded
    }
    
    /**
     * 等待VPN状态变为目标状态
     */
    private suspend fun waitForVpnState(targetState: Boolean): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < VPN_STATE_TIMEOUT_MS) {
            if (DnsVpnService.isServiceRunning == targetState) {
                _isVpnActuallyRunning.value = targetState
                return true
            }
            delay(POLL_INTERVAL_MS)
        }
        _isVpnActuallyRunning.value = DnsVpnService.isServiceRunning
        return false
    }

    fun requestVpnPermission(): Intent? {
        return VpnService.prepare(context)
    }

    /**
     * 暂停轮询（应用进入后台时调用）
     */
    fun pause() {
        _isPaused.value = true
    }

    /**
     * 恢复轮询（应用回到前台时调用）
     */
    fun resume() {
        _isPaused.value = false
    }

    override fun onCleared() {
        super.onCleared()
        // ViewModel 销毁时刷新统计信息
        viewModelScope.launch {
            statisticsBuffer.flush()
        }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val prefs = ServiceLocator.providePreferencesManager()
            val filterRepo = ServiceLocator.provideFilterListRepository() as FilterListRepositoryImpl
            return DashboardViewModel(
                context,
                prefs,
                ServiceLocator.provideStatisticsBuffer(),
                filterRepo,
                ServiceLocator.provideDomainFilter()
            ) as T
        }
    }
}
