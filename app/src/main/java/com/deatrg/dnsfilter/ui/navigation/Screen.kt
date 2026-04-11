package com.deatrg.dnsfilter.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(
    val route: String,
    val title: String,
    val icon: ImageVector
) {
    data object Dashboard : Screen("dashboard", "Dashboard", Icons.Default.Dashboard)
    data object DnsServers : Screen("dns_servers", "DNS", Icons.Default.Dns)
    data object FilterLists : Screen("filter_lists", "Filters", Icons.Default.Block)
}
