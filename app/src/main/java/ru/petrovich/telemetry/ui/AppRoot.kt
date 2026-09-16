package ru.petrovich.telemetry.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ru.petrovich.telemetry.ServiceLocator
import ru.petrovich.telemetry.ui.anomalies.AnomaliesScreen
import ru.petrovich.telemetry.ui.charts.ChartsScreen
import ru.petrovich.telemetry.ui.chat.ChatScreen
import ru.petrovich.telemetry.ui.common.TelemetryViewModel
import ru.petrovich.telemetry.ui.settings.SettingsScreen
import ru.petrovich.telemetry.ui.tables.TablesScreen

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    CHAT("chat", "Чат", Icons.Filled.Forum),
    ANOMALIES("anomalies", "Аномалии", Icons.Filled.Warning),
    TABLES("tables", "Данные", Icons.Filled.TableChart),
    CHARTS("charts", "Графики", Icons.Filled.Insights),
}

private const val SETTINGS_ROUTE = "settings"

@Composable
fun AppRoot(requestedTab: String?, onTabHandled: () -> Unit) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    // Общий ViewModel для таблиц и графиков: выбранная машина и период сохраняются при переключении.
    val telemetryVm: TelemetryViewModel = viewModel()
    val anomalies by ServiceLocator.anomalyStore.anomalies.collectAsStateWithLifecycle()
    val unread = anomalies.count { !it.acknowledged }

    fun open(r: String) = nav.navigate(r) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    LaunchedEffect(requestedTab) {
        if (requestedTab != null) {
            open(requestedTab)
            onTabHandled()
        }
    }

    Scaffold(
        // Отступы сверху обрабатывают TopAppBar на экранах.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (route != SETTINGS_ROUTE) {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = { open(tab.route) },
                            icon = {
                                if (tab == Tab.ANOMALIES && unread > 0) {
                                    BadgedBox(badge = { Badge { Text(if (unread > 99) "99+" else "$unread") } }) {
                                        Icon(tab.icon, null)
                                    }
                                } else {
                                    Icon(tab.icon, null)
                                }
                            },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Tab.CHAT.route, modifier = Modifier.padding(padding)) {
            val openSettings = { nav.navigate(SETTINGS_ROUTE) }
            composable(Tab.CHAT.route) { ChatScreen(onOpenSettings = openSettings) }
            composable(Tab.ANOMALIES.route) { AnomaliesScreen(onOpenSettings = openSettings) }
            composable(Tab.TABLES.route) { TablesScreen(telemetryVm, onOpenSettings = openSettings) }
            composable(Tab.CHARTS.route) { ChartsScreen(telemetryVm, onOpenSettings = openSettings) }
            composable(SETTINGS_ROUTE) {
                SettingsScreen(onBack = { nav.popBackStack() }, onChanged = { telemetryVm.reloadVehicles() })
            }
        }
    }
}
