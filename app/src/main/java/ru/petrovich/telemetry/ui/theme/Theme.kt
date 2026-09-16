package ru.petrovich.telemetry.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Light = lightColorScheme(
    primary = Color(0xFF1E4E8C),
    secondary = Color(0xFF5A6A7E),
    tertiary = Color(0xFFB26A00),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFA6C8FF),
    secondary = Color(0xFFBEC7D6),
    tertiary = Color(0xFFFFB95C),
)

val SeverityCritical = Color(0xFFD32F2F)
val SeverityWarning = Color(0xFFF57C00)
val SeverityInfo = Color(0xFF1976D2)

@Composable
fun TelemetryTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
