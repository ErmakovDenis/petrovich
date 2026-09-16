package ru.petrovich.telemetry

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
            TelemetryTheme {
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
        intent?.getStringExtra(EXTRA_OPEN_TAB)?.let { pendingTab.value = it }
    }

    companion object {
        const val EXTRA_OPEN_TAB = "open_tab"
        const val TAB_ANOMALIES = "anomalies"
    }
}
