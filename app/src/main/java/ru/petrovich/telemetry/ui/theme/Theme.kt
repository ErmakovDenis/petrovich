package ru.petrovich.telemetry.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.petrovich.telemetry.R

/** Палитра из прототипа: зелёный акцент, три уровня срочности, мягкие фоны для плашек. */
@Immutable
class PetrovichColors(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val line: Color,
    val ink: Color,
    val muted: Color,
    val faint: Color,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val high: Color,
    val highSoft: Color,
    val med: Color,
    val medSoft: Color,
    val low: Color,
    val lowSoft: Color,
    val ok: Color,
    val okSoft: Color,
    val dark: Boolean,
)

private val LightColors = PetrovichColors(
    bg = Color(0xFFF2F5F2), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFE9EEEA), line = Color(0xFFD6DDD8),
    ink = Color(0xFF15201B), muted = Color(0xFF5F6D66), faint = Color(0xFF8C9A93),
    accent = Color(0xFF1E7A4C), onAccent = Color(0xFFFFFFFF), accentSoft = Color(0xFFDCEFE3),
    high = Color(0xFFC93A2B), highSoft = Color(0xFFFBE3DF),
    med = Color(0xFFB77A0C), medSoft = Color(0xFFFAEBCB),
    low = Color(0xFF4E6B8A), lowSoft = Color(0xFFE0E8F1),
    ok = Color(0xFF2A9258), okSoft = Color(0xFFDDF1E4),
    dark = false,
)

private val DarkColors = PetrovichColors(
    bg = Color(0xFF111916), surface = Color(0xFF18221E), surface2 = Color(0xFF202C27), line = Color(0xFF2C3934),
    ink = Color(0xFFE6EEE9), muted = Color(0xFF9AAAA2), faint = Color(0xFF6E7E77),
    accent = Color(0xFF3DAE72), onAccent = Color(0xFF06140D), accentSoft = Color(0xFF1B3527),
    high = Color(0xFFF0705F), highSoft = Color(0xFF3A1E1A),
    med = Color(0xFFE3AE45), medSoft = Color(0xFF382C14),
    low = Color(0xFF8FB0D2), lowSoft = Color(0xFF1D2A38),
    ok = Color(0xFF52C185), okSoft = Color(0xFF173224),
    dark = true,
)

private val LocalPetrovichColors = staticCompositionLocalOf { LightColors }

object Petrovich {
    val colors: PetrovichColors
        @Composable @ReadOnlyComposable get() = LocalPetrovichColors.current

    /** Шрифт для крупных цифр и заголовков (Unbounded). */
    val display: FontFamily get() = DisplayFamily
}

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) =
    Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

private val UiFamily = FontFamily(
    variable(R.font.onest, 400), variable(R.font.onest, 500), variable(R.font.onest, 600),
)
private val DisplayFamily = FontFamily(variable(R.font.unbounded, 500), variable(R.font.unbounded, 700))

private fun ui(size: Int, weight: FontWeight = FontWeight.Normal, line: Int = (size * 1.4).toInt(), spacing: Double = 0.0) =
    TextStyle(fontFamily = UiFamily, fontSize = size.sp, fontWeight = weight, lineHeight = line.sp, letterSpacing = spacing.sp)

private val AppTypography = Typography(
    displaySmall = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 32.sp),
    headlineMedium = ui(22, FontWeight.Bold, 27),
    headlineSmall = ui(20, FontWeight.Bold, 26),
    titleLarge = ui(19, FontWeight.SemiBold, 24),
    titleMedium = ui(16, FontWeight.SemiBold, 22),
    titleSmall = ui(15, FontWeight.SemiBold, 20),
    bodyLarge = ui(16, line = 23),
    bodyMedium = ui(15, line = 21),
    bodySmall = ui(13, line = 18),
    labelLarge = ui(15, FontWeight.SemiBold, 20),
    labelMedium = ui(13, FontWeight.Medium, 17),
    labelSmall = ui(12, FontWeight.Medium, 16),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun TelemetryTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) DarkColors else LightColors
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.accent, onPrimary = c.onAccent, primaryContainer = c.accentSoft, onPrimaryContainer = c.accent,
            secondary = c.muted, secondaryContainer = c.surface2, onSecondaryContainer = c.ink,
            tertiary = c.med, background = c.bg, onBackground = c.ink,
            surface = c.surface, onSurface = c.ink, surfaceVariant = c.surface2, onSurfaceVariant = c.muted,
            surfaceContainerLowest = c.bg, surfaceContainerLow = c.surface, surfaceContainer = c.surface,
            surfaceContainerHigh = c.surface2, surfaceContainerHighest = c.surface2,
            outline = c.faint, outlineVariant = c.line, error = c.high, errorContainer = c.highSoft, onErrorContainer = c.high,
        )
    } else {
        lightColorScheme(
            primary = c.accent, onPrimary = c.onAccent, primaryContainer = c.accentSoft, onPrimaryContainer = c.accent,
            secondary = c.muted, secondaryContainer = c.surface2, onSecondaryContainer = c.ink,
            tertiary = c.med, background = c.bg, onBackground = c.ink,
            surface = c.surface, onSurface = c.ink, surfaceVariant = c.surface2, onSurfaceVariant = c.muted,
            surfaceContainerLowest = c.bg, surfaceContainerLow = c.surface, surfaceContainer = c.surface,
            surfaceContainerHigh = c.surface2, surfaceContainerHighest = c.surface2,
            outline = c.faint, outlineVariant = c.line, error = c.high, errorContainer = c.highSoft, onErrorContainer = c.high,
        )
    }
    CompositionLocalProvider(LocalPetrovichColors provides c) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes) {
            // Text без явного стиля берёт Onest, а не системный шрифт.
            ProvideTextStyle(AppTypography.bodyMedium.copy(color = c.ink), content)
        }
    }
}
