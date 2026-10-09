package ru.petrovich.telemetry.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WarningAmber
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
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
import kotlinx.coroutines.launch
import ru.petrovich.telemetry.MainActivity
import ru.petrovich.telemetry.ServiceLocator
import ru.petrovich.telemetry.ui.action.ActionScreen
import ru.petrovich.telemetry.ui.charts.ChartsScreen
import ru.petrovich.telemetry.ui.chat.ChatScreen
import ru.petrovich.telemetry.ui.chat.ChatViewModel
import ru.petrovich.telemetry.ui.common.TelemetryViewModel
import ru.petrovich.telemetry.ui.common.isNew
import ru.petrovich.telemetry.ui.detail.AnomalyDetailScreen
import ru.petrovich.telemetry.ui.home.HomeScreen
import ru.petrovich.telemetry.ui.home.WidgetDetailScreen
import ru.petrovich.telemetry.ui.home.WidgetType
import ru.petrovich.telemetry.ui.home.WidgetsScreen
import ru.petrovich.telemetry.ui.mail.MailScreen
import ru.petrovich.telemetry.ui.notifications.NotificationsScreen
import ru.petrovich.telemetry.ui.onboarding.OnboardingScreen
import ru.petrovich.telemetry.ui.park.ParkScreen
import ru.petrovich.telemetry.ui.park.VehicleCardScreen
import ru.petrovich.telemetry.ui.park.VehicleMapScreen
import ru.petrovich.telemetry.ui.problems.ProblemsScreen
import ru.petrovich.telemetry.ui.problems.ProblemsTab
import ru.petrovich.telemetry.ui.report.ReportScreen
import ru.petrovich.telemetry.ui.resolution.ResolutionScreen
import ru.petrovich.telemetry.ui.settings.ConnectionSettingsScreen
import ru.petrovich.telemetry.ui.settings.SettingsScreen
import ru.petrovich.telemetry.ui.tables.TablesScreen
import ru.petrovich.telemetry.ui.theme.Petrovich

private const val HOME = "home"
private const val CHAT = "chat?anomaly={anomaly}"
private const val PROBLEMS = "problems"
private const val SETTINGS = "settings"
private const val CARD = "card/{id}"
private const val ACTION = "action/{id}"
private const val MAIL = "mail/{id}"
private const val RESOLUTION = "resolution/{id}"
private const val REPORT = "report"
private const val WIDGETS = "widgets"
private const val WIDGET_DETAIL = "widget/{type}"
private const val NOTIFICATIONS = "notifications"
private const val PARK = "park"
private const val VEHICLE = "vehicle/{id}"
private const val VEHICLE_MAP = "vehicle/{id}/map"
private const val CONNECTION = "connection"
private const val TABLES = "tables"
private const val CHARTS = "charts"

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    HOME(ru.petrovich.telemetry.ui.HOME, "Главная", Icons.Outlined.Home),
    PARK(ru.petrovich.telemetry.ui.PARK, "Парк", Icons.Filled.LocalShipping),
    CHAT(ru.petrovich.telemetry.ui.CHAT, "Петрович", Icons.Outlined.ChatBubbleOutline),
    PROBLEMS(ru.petrovich.telemetry.ui.PROBLEMS, "Проблемы", Icons.Outlined.WarningAmber),
    SETTINGS(ru.petrovich.telemetry.ui.SETTINGS, "Настройки", Icons.Outlined.Settings),
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
    val notifications by ServiceLocator.notificationStore.items.collectAsStateWithLifecycle()
    val unreadNotifications = notifications.count { !it.read }
    var problemsTab by rememberSaveable { mutableStateOf(ProblemsTab.NEW) }
    val scope = rememberCoroutineScope()
    // История чата живёт на уровне Activity и не теряется при смене вкладки.
    val chatVm: ChatViewModel = viewModel(viewModelStoreOwner = checkNotNull(LocalViewModelStoreOwner.current))

    // Стек вкладки не сохраняем: иначе экраны, открытые поверх вкладки (отчёт, чат из карточки),
    // «прилипают» и возвращаются при следующем переключении на неё.
    fun openTab(r: String) = nav.navigate(r) {
        popUpTo(nav.graph.findStartDestination().id)
        launchSingleTop = true
    }
    fun push(r: String) = nav.navigate(r) { launchSingleTop = true }
    fun openProblems(tab: ProblemsTab) { problemsTab = tab; openTab(PROBLEMS) }
    fun openCard(id: String) = push("card/${Uri.encode(id)}")
    fun openAction(id: String) = push("action/${Uri.encode(id)}")
    fun openMail(id: String) = push("mail/${Uri.encode(id)}")
    // «Разбор» и «Письмо» со стека убираем: назад из «Хода разбора» должен вести на карточку события, а не на них.
    fun openResolution(id: String) = nav.navigate("resolution/${Uri.encode(id)}") {
        popUpTo(CARD) { inclusive = false }
        launchSingleTop = true
    }
    fun openChat(anomalyId: String?) = push(if (anomalyId == null) "chat" else "chat?anomaly=${Uri.encode(anomalyId)}")
    fun openVehicle(id: String) = push("vehicle/${Uri.encode(id)}")
    fun openWidgetDetail(type: WidgetType) = push("widget/${type.key}")
    fun openNotifications() = push(NOTIFICATIONS)

    LaunchedEffect(requestedTab) {
        if (requestedTab == MainActivity.TAB_ANOMALIES) {
            openProblems(ProblemsTab.NEW)
            onTabHandled()
        } else if (requestedTab != null) onTabHandled()
    }

    val openConnection = { push(CONNECTION) }
    Scaffold(
        // Отступы сверху обрабатывают TopAppBar и шапки экранов.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Petrovich.colors.bg,
        bottomBar = {
            if (route == HOME || route == PARK || route == PROBLEMS || route == SETTINGS || route == CHAT) {
                NavigationBar(containerColor = Petrovich.colors.surface) {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = { openTab(if (tab == Tab.CHAT) "chat" else tab.route) },
                            icon = {
                                if (tab == Tab.PROBLEMS && pending > 0) {
                                    BadgedBox(badge = { Badge(containerColor = Petrovich.colors.high) { Text(if (pending > 99) "99+" else "$pending") } }) {
                                        Icon(tab.icon, null)
                                    }
                                } else Icon(tab.icon, null)
                            },
                            label = { Text(tab.label, maxLines = 1, softWrap = false, fontSize = 10.sp) },
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
                    onOpenProblems = ::openProblems,
                    onOpenCard = ::openCard,
                    onOpenReport = { push(REPORT) },
                    onOpenChat = { openChat(null) },
                    onOpenWidgets = { push(WIDGETS) },
                    onOpenTables = { push(TABLES) },
                    onOpenCharts = { push(CHARTS) },
                    unreadNotifications = unreadNotifications,
                    onOpenNotifications = ::openNotifications,
                    onOpenFleet = { openTab(PARK) },
                    onOpenWidgetDetail = ::openWidgetDetail,
                )
            }
            composable(PARK) { ParkScreen(onOpenVehicle = ::openVehicle) }
            composable(VEHICLE, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                VehicleCardScreen(
                    vehicleId = entry.arguments?.getString("id").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onShowOnMap = { push("vehicle/${Uri.encode(it)}/map") },
                    onOpenAnomaly = ::openCard,
                    onAsk = { openChat(null) },
                )
            }
            composable(VEHICLE_MAP, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                VehicleMapScreen(vehicleId = entry.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
            }
            composable(WIDGET_DETAIL, arguments = listOf(navArgument("type") { type = NavType.StringType })) { entry ->
                val type = WidgetType.byKey(entry.arguments?.getString("type").orEmpty())
                if (type != null) {
                    WidgetDetailScreen(type = type, onBack = { nav.popBackStack() }, onAsk = { openChat(null) }, onOpenVehicle = ::openVehicle)
                }
            }
            composable(NOTIFICATIONS) { NotificationsScreen(onBack = { nav.popBackStack() }, onOpenCard = ::openCard) }
            composable(PROBLEMS) {
                ProblemsScreen(problemsTab, onTab = { problemsTab = it }, onOpenCard = ::openCard, onAsk = { openChat(it) })
            }
            composable(
                CHAT,
                arguments = listOf(navArgument("anomaly") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) { entry ->
                val anomalyId = entry.arguments?.getString("anomaly")
                ChatScreen(anomalyId, onBack = if (anomalyId != null) ({ nav.popBackStack() }) else null, onOpenCard = ::openCard, vm = chatVm)
            }
            composable(CARD, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                AnomalyDetailScreen(
                    anomalyId = entry.arguments?.getString("id").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onAct = ::openAction,
                    // Ссылка «машина · время» и кнопка «Машина» (молчит датчик) ведут на карточку машины (3.13).
                    onOpenCharts = ::openVehicle,
                    onOpenResolution = ::openResolution,
                )
            }
            composable(ACTION, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                ActionScreen(
                    anomalyId = entry.arguments?.getString("id").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onMail = ::openMail,
                    onResolved = ::openResolution,
                )
            }
            composable(MAIL, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                MailScreen(
                    anomalyId = entry.arguments?.getString("id").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onSent = ::openResolution,
                )
            }
            composable(RESOLUTION, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                ResolutionScreen(
                    anomalyId = entry.arguments?.getString("id").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onOpenCard = ::openCard,
                )
            }
            composable(REPORT) {
                ReportScreen(
                    onBack = { nav.popBackStack() },
                    onOpenCard = ::openCard,
                    onOpenProblems = { openProblems(ProblemsTab.NEW) },
                    onOpenSettings = { openTab(SETTINGS) },
                    onAsk = { openChat(null) },
                )
            }
            composable(WIDGETS) { WidgetsScreen(onBack = { nav.popBackStack() }) }
            composable(TABLES) { TablesScreen(telemetryVm, onBack = { nav.popBackStack() }, onOpenSettings = openConnection) }
            composable(CHARTS) { ChartsScreen(telemetryVm, onBack = { nav.popBackStack() }, onOpenSettings = openConnection) }
            composable(SETTINGS) {
                SettingsScreen(
                    onOpenConnection = openConnection,
                    onRestartOnboarding = { scope.launch { ServiceLocator.settings.update { it.copy(onboarded = false) } } },
                )
            }
            composable(CONNECTION) {
                ConnectionSettingsScreen(onBack = { nav.popBackStack() }, onChanged = { telemetryVm.reloadVehicles() })
            }
        }
    }
}
