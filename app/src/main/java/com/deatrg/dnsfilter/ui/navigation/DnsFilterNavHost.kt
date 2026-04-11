package com.deatrg.dnsfilter.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.deatrg.dnsfilter.ui.screens.dashboard.DashboardScreen
import com.deatrg.dnsfilter.ui.screens.dnsserver.DnsServersScreen
import com.deatrg.dnsfilter.ui.screens.filterlist.FilterListsScreen

@Composable
fun DnsFilterNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Dashboard.route,
        modifier = modifier
    ) {
        composable(Screen.Dashboard.route) {
            DashboardScreen()
        }
        composable(Screen.DnsServers.route) {
            DnsServersScreen()
        }
        composable(Screen.FilterLists.route) {
            FilterListsScreen()
        }
    }
}
