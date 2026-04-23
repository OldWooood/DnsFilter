package com.deatrg.dnsfilter.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector
import com.deatrg.dnsfilter.R

sealed class Screen(
    val route: String,
    @param:StringRes val titleRes: Int,
    val icon: ImageVector,
    val filledIcon: ImageVector
) {
    data object Dashboard : Screen("dashboard", R.string.nav_dashboard, Icons.Outlined.Dashboard, Icons.Filled.Dashboard)
    data object DnsServers : Screen("dns_servers", R.string.nav_dns, Icons.Outlined.Dns, Icons.Filled.Dns)
    data object FilterLists : Screen("filter_lists", R.string.nav_filters, Icons.Outlined.Block, Icons.Filled.Block)
}
