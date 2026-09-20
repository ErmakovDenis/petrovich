package ru.petrovich.telemetry.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ru.petrovich.telemetry.MainActivity
import ru.petrovich.telemetry.ServiceLocator
import ru.petrovich.telemetry.ui.charts.ChartsScreen
import ru.petrovich.telemetry.ui.chat.ChatScreen
import ru.petrovich.telemetry.ui.chat.ChatViewModel
import ru.petrovich.telemetry.ui.common.TelemetryViewModel
import ru.petrovich.telemetry.ui.common.isNew
import ru.petrovich.telemetry.ui.detail.AnomalyDetailScreen
import ru.petrovich.telemetry.ui.feed.FeedFilter
import ru.petrovich.telemetry.ui.feed.FeedScreen
import ru.petrovich.telemetry.ui.home.HomeScreen
import ru.petrovich.telemetry.ui.onboarding.OnboardingScreen
import ru.petrovich.telemetry.ui.report.ReportScreen
import ru.petrovich.telemetry.ui.settings.NotifSettingsScreen
import ru.petrovich.telemetry.ui.settings.SettingsScreen
import ru.petrovich.telemetry.ui.tables.TablesScreen
import ru.petrovich.telemetry.ui.theme.Petrovich

private const val HOME = "home"
private const val FEED = "feed"
private const val CHAT = "chat?anomaly={anomaly}"
private const val CARD = "card/{id}"
private const val REPORT = "report"
private const val NOTIF = "notif"
private const val SETTINGS = "settings"
private const val TABLES = "tables"
private const val CHARTS = "charts"

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    HOME(ru.petrovich.telemetry.ui.HOME, "Сводка", Icons.Outlined.GridView),
    FEED(ru.petrovich.telemetry.ui.FEED, "Уведомления", Icons.Outlined.Notifications),
    CHAT(ru.petrovich.telemetry.ui.CHAT, "Петрович", Icons.Outlined.ChatBubbleOutline),
}

@Composable
fun AppRoot(requestedTab: String?, onTabHandled: () -> Unit) {
    val settings by ServiceLocator.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val s = settings
    // Пока настройки читаются с диска — просто фон, чтобы не мигать онбордингом.
    if (s == null) {
        Box(Modifier.fillMaxSize().background(Petrovich.colors.bg))
        return
    }
    // Общий ViewModel для таблиц и графиков: выбранная машина и период сохраняются при переключении.
    val telemetryVm: TelemetryViewModel = viewModel()
    if (!s.onboarded) {
        OnboardingScreen(onConnected = { telemetryVm.reloadVehicles() })
        return
    }
    MainNavigation(telemetryVm, requestedTab, onTabHandled)
}

@Composable
private fun MainNavigation(telemetryVm: TelemetryViewModel, requestedTab: String?, onTabHandled: () -> Unit) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val telemetry by telemetryVm.state.collectAsStateWithLifecycle()
    val anomalies by ServiceLocator.anomalyStore.anomalies.collectAsStateWithLifecycle()
    val pending = anomalies.count { it.isNew }
    var feedFilter by rememberSaveable { mutableStateOf(FeedFilter.ALL) }
    // История чата живёт на уровне Activity и не теряется при смене вкладки.
    val chatVm: ChatViewModel = viewModel(viewModelStoreOwner = checkNotNull(LocalViewModelStoreOwner.current))

    // Стек вкладки не сохраняем: иначе экраны, открытые поверх вкладки (отчёт, чат из карточки),
    // «прилипают» и возвращаются при следующем переключении на неё.
    fun openTab(r: String) = nav.navigate(r) {
        popUpTo(nav.graph.findStartDestination().id)
        launchSingleTop = true
    }
    fun push(r: String) = nav.navigate(r) { launchSingleTop = true }
    fun openFeed(filter: FeedFilter) { feedFilter = filter; openTab(FEED) }
    fun openCard(id: String) = push("card/${Uri.encode(id)}")
    fun openChat(anomalyId: String?) = push(if (anomalyId == null) "chat" else "chat?anomaly=${Uri.encode(anomalyId)}")

    LaunchedEffect(requestedTab) {
        if (requestedTab == MainActivity.TAB_ANOMALIES) {
            openFeed(FeedFilter.NEW)
            onTabHandled()
        } else if (requestedTab != null) onTabHandled()
    }

    val settingsRoute = { push(SETTINGS) }
    Scaffold(
        // Отступы сверху обрабатывают TopAppBar и шапки экранов.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Petrovich.colors.bg,
        bottomBar = {
            if (route == HOME || route == FEED || route == CHAT) {
                NavigationBar(containerColor = Petrovich.colors.surface) {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = { openTab(if (tab == Tab.CHAT) "chat" else tab.route) },
                            icon = {
                                if (tab == Tab.FEED && pending > 0) {
                                    BadgedBox(badge = { Badge(containerColor = Petrovich.colors.high) { Text(if (pending > 99) "99+" else "$pending") } }) {
                                        Icon(tab.icon, null)
                                    }
                                } else Icon(tab.icon, null)
                            },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Petrovich.colors.accent, selectedTextColor = Petrovich.colors.accent,
                                unselectedIconColor = Petrovich.colors.faint, unselectedTextColor = Petrovich.colors.faint,
                                indicatorColor = Petrovich.colors.accentSoft,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = HOME, modifier = Modifier.padding(padding)) {
            composable(HOME) {
                HomeScreen(
                    vehicleCount = telemetry.vehicles.size.takeIf { it > 0 },
                    onOpenFeed = ::openFeed,
                    onOpenCard = ::openCard,
                    onOpenReport = { push(REPORT) },
                    onOpenSettings = settingsRoute,
                    onOpenTables = { push(TABLES) },
                    onOpenCharts = { push(CHARTS) },
                )
            }
            composable(FEED) {
                FeedScreen(feedFilter, onFilter = { feedFilter = it }, onOpenCard = ::openCard, onOpenNotifSettings = { push(NOTIF) })
            }
            composable(
                CHAT,
                arguments = listOf(navArgument("anomaly") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) { entry ->
                val anomalyId = entry.arguments?.getString("anomaly")
                ChatScreen(anomalyId, onBack = if (anomalyId != null) ({ nav.popBackStack() }) else null, vm = chatVm)
            }
            composable(CARD, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                AnomalyDetailScreen(
                    anomalyId = entry.arguments?.getString("id").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onAsk = ::openChat,
                    onOpenCharts = { vehicleId -> telemetryVm.selectVehicle(vehicleId); push(CHARTS) },
                )
            }
            composable(REPORT) { ReportScreen(onBack = { nav.popBackStack() }, onOpenCard = ::openCard, onAsk = { openChat(null) }) }
            composable(NOTIF) { NotifSettingsScreen(onBack = { nav.popBackStack() }) }
            composable(TABLES) { TablesScreen(telemetryVm, onBack = { nav.popBackStack() }, onOpenSettings = settingsRoute) }
            composable(CHARTS) { ChartsScreen(telemetryVm, onBack = { nav.popBackStack() }, onOpenSettings = settingsRoute) }
            composable(SETTINGS) {
                SettingsScreen(onBack = { nav.popBackStack() }, onChanged = { telemetryVm.reloadVehicles() })
            }
        }
    }
}
