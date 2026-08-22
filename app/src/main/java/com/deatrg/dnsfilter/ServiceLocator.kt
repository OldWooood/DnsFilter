package com.deatrg.dnsfilter

import android.annotation.SuppressLint
import android.content.Context
import com.deatrg.dnsfilter.data.local.PreferencesManager
import com.deatrg.dnsfilter.data.local.StatisticsBuffer
import com.deatrg.dnsfilter.data.remote.DomainFilter
import com.deatrg.dnsfilter.data.repository.DnsServerRepositoryImpl
import com.deatrg.dnsfilter.data.repository.FilterListRepositoryImpl
import com.deatrg.dnsfilter.domain.repository.DnsServerRepository
import com.deatrg.dnsfilter.domain.repository.FilterListRepository
import com.deatrg.dnsfilter.service.VpnStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

@SuppressLint("StaticFieldLeak")
object ServiceLocator {
    @SuppressLint("StaticFieldLeak")
    @Volatile
    private var context: Context? = null

    @Volatile
    private var okHttpClient: OkHttpClient? = null

    @SuppressLint("StaticFieldLeak")
    @Volatile
    private var preferencesManager: PreferencesManager? = null

    @Volatile
    private var domainFilter: DomainFilter? = null

    @Volatile
    private var statisticsBuffer: StatisticsBuffer? = null

    @Volatile
    private var vpnStateHolder: VpnStateHolder? = null

    @Volatile
    private var dnsServerRepository: DnsServerRepository? = null

    @Volatile
    private var filterListRepository: FilterListRepository? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun init(ctx: Context) {
        context = ctx.applicationContext
    }

    private fun getContext(): Context {
        return context ?: throw IllegalStateException("ServiceLocator not initialized")
    }

    fun provideOkHttpClient(): OkHttpClient {
        return okHttpClient ?: synchronized(this) {
            okHttpClient ?: OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build().also { okHttpClient = it }
        }
    }

    fun providePreferencesManager(): PreferencesManager {
        return preferencesManager ?: synchronized(this) {
            preferencesManager ?: PreferencesManager(getContext()).also { preferencesManager = it }
        }
    }

    fun provideDomainFilter(): DomainFilter {
        return domainFilter ?: synchronized(this) {
            domainFilter ?: DomainFilter(
                getContext(),
                provideOkHttpClient()
            ).also { domainFilter = it }
        }
    }

    fun provideStatisticsBuffer(): StatisticsBuffer {
        return statisticsBuffer ?: synchronized(this) {
            statisticsBuffer ?: StatisticsBuffer(
                scope
            ).also { statisticsBuffer = it }
        }
    }

    fun provideVpnStateHolder(): VpnStateHolder {
        return vpnStateHolder ?: synchronized(this) {
            vpnStateHolder ?: VpnStateHolder().also { vpnStateHolder = it }
        }
    }

    fun provideDnsServerRepository(): DnsServerRepository {
        return dnsServerRepository ?: synchronized(this) {
            dnsServerRepository ?: DnsServerRepositoryImpl(
                providePreferencesManager()
            ).also { dnsServerRepository = it }
        }
    }

    fun provideFilterListRepository(): FilterListRepository {
        return filterListRepository ?: synchronized(this) {
            filterListRepository ?: FilterListRepositoryImpl(
                providePreferencesManager(),
                provideDomainFilter()
            ).also { filterListRepository = it }
        }
    }
}
