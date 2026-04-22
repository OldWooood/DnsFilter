package com.deatrg.dnsfilter.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(
    val route: String,
    val title: String,
    val icon: ImageVector,
    val filledIcon: ImageVector
) {
    data object Dashboard : Screen("dashboard", "Dashboard", Icons.Outlined.Dashboard, Icons.Filled.Dashboard)
    data object DnsServers : Screen("dns_servers", "DNS", Icons.Outlined.Dns, Icons.Filled.Dns)
    data object FilterLists : Screen("filter_lists", "Filters", Icons.Outlined.Block, Icons.Filled.Block)
}
