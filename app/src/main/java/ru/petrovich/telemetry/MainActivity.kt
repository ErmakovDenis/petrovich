package ru.petrovich.telemetry

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.graphics.Color
import ru.petrovich.telemetry.data.settings.ThemeMode
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import ru.petrovich.telemetry.ui.AppRoot
import ru.petrovich.telemetry.ui.theme.TelemetryTheme

class MainActivity : ComponentActivity() {

    /** Вкладка, которую нужно открыть (например, из уведомления). */
    private val pendingTab = mutableStateOf<String?>(null)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !ServiceLocator.notifier.canNotify()) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            val settings by ServiceLocator.settings.settings.collectAsStateWithLifecycle(initialValue = null)
            val dark = when (settings?.themeMode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                else -> isSystemInDarkTheme()
            }
            // Цвет значков в статус-баре и панели навигации должен следовать выбранной теме, а не системной.
            DisposableEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            TelemetryTheme(dark = dark) {
                AppRoot(
                    requestedTab = pendingTab.value,
                    onTabHandled = { pendingTab.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        // Activity экспортирована (LAUNCHER): принимаем только известные значения, иначе навигация упадёт.
        intent?.getStringExtra(EXTRA_OPEN_TAB)?.takeIf { it == TAB_ANOMALIES }?.let { pendingTab.value = it }
    }

    companion object {
        const val EXTRA_OPEN_TAB = "open_tab"
        const val TAB_ANOMALIES = "anomalies"
    }
}
