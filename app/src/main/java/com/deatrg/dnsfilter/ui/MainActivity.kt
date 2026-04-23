package com.deatrg.dnsfilter.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.deatrg.dnsfilter.ServiceLocator
import com.deatrg.dnsfilter.ui.navigation.Screen
import com.deatrg.dnsfilter.ui.screens.dashboard.DashboardScreen
import com.deatrg.dnsfilter.ui.screens.dnsserver.DnsServersScreen
import com.deatrg.dnsfilter.ui.screens.filterlist.FilterListsScreen
import com.deatrg.dnsfilter.ui.theme.DnsFilterTheme
import androidx.compose.ui.res.stringResource

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ServiceLocator.init(applicationContext)
        enableEdgeToEdge()
        setContent {
            DnsFilterTheme {
                MainScreen()
            }
        }
    }
}

@Composable
private fun MainScreen() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ) {
                val screens = listOf(
                    Screen.Dashboard,
                    Screen.DnsServers,
                    Screen.FilterLists
                )
                screens.forEach { screen ->
                    val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                    NavigationBarItem(
                        icon = {
                            AnimatedContent(
                                targetState = selected,
                                transitionSpec = { scaleIn() + fadeIn() togetherWith scaleOut() + fadeOut() },
                                label = "icon_anim"
                            ) { isSelected ->
                                Icon(
                                    imageVector = if (isSelected) screen.filledIcon else screen.icon,
                                    contentDescription = stringResource(screen.titleRes),
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        },
                        label = { Text(stringResource(screen.titleRes), style = MaterialTheme.typography.labelMedium) },
                        selected = selected,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                            indicatorColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Dashboard.route,
            modifier = Modifier.padding(innerPadding)
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
}
