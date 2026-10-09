package ru.petrovich.telemetry.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Shapes
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

/** Палитра из макетов «Петрович»: светлый серо-голубой фон, тёмно-синий акцент, три уровня срочности. */
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
    bg = Color(0xFFF4F5F7), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFEBEEF1), line = Color(0xFFE0E4E8),
    ink = Color(0xFF1B1F24), muted = Color(0xFF5B616B), faint = Color(0xFF8A9099),
    accent = Color(0xFF1D3F66), onAccent = Color(0xFFFFFFFF), accentSoft = Color(0xFFE3EAF3),
    high = Color(0xFFB42318), highSoft = Color(0xFFFDECEA),
    med = Color(0xFF8A4B00), medSoft = Color(0xFFFFF4D6),
    low = Color(0xFF3E5F85), lowSoft = Color(0xFFE3EAF3),
    ok = Color(0xFF067647), okSoft = Color(0xFFE6F4EC),
    dark = false,
)

private val DarkColors = PetrovichColors(
    bg = Color(0xFF161A1E), surface = Color(0xFF1F252B), surface2 = Color(0xFF272E35), line = Color(0xFF363E46),
    ink = Color(0xFFEDEEF0), muted = Color(0xFF9BA3AD), faint = Color(0xFF6C7580),
    accent = Color(0xFF6E9BD6), onAccent = Color(0xFF0B1520), accentSoft = Color(0xFF223246),
    high = Color(0xFFFF8A75), highSoft = Color(0xFF3A1D18),
    med = Color(0xFFF0B429), medSoft = Color(0xFF3A2C10),
    low = Color(0xFF8FB0D2), lowSoft = Color(0xFF1D2A38),
    ok = Color(0xFF4CC38A), okSoft = Color(0xFF14301F),
    dark = true,
)

private val LocalPetrovichColors = staticCompositionLocalOf { LightColors }

object Petrovich {
    val colors: PetrovichColors
        @Composable @ReadOnlyComposable get() = LocalPetrovichColors.current

    /** Шрифт для крупных цифр и заголовков (Manrope). */
    val display: FontFamily get() = DisplayFamily
}

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) =
    Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

private val UiFamily = FontFamily(
    variable(R.font.onest, 400), variable(R.font.onest, 500), variable(R.font.onest, 600),
)
private val DisplayFamily = FontFamily(
    variable(R.font.manrope, 600), variable(R.font.manrope, 700), variable(R.font.manrope, 800),
)

private fun ui(size: Int, weight: FontWeight = FontWeight.Normal, line: Int = (size * 1.4).toInt(), spacing: Double = 0.0) =
    TextStyle(fontFamily = UiFamily, fontSize = size.sp, fontWeight = weight, lineHeight = line.sp, letterSpacing = spacing.sp)

private fun display(size: Int, weight: FontWeight, line: Int) =
    TextStyle(fontFamily = DisplayFamily, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp)

private val AppTypography = Typography(
    displaySmall = display(28, FontWeight.Black, 32),
    headlineMedium = display(24, FontWeight.Black, 29),
    headlineSmall = display(20, FontWeight.Black, 25),
    titleLarge = display(19, FontWeight.ExtraBold, 24),
    titleMedium = display(16, FontWeight.ExtraBold, 21),
    titleSmall = display(15, FontWeight.Bold, 19),
    bodyLarge = ui(16, line = 23),
    bodyMedium = ui(15, line = 21),
    bodySmall = ui(13, line = 18),
    labelLarge = ui(16, FontWeight.Bold, 20),
    labelMedium = ui(14, FontWeight.SemiBold, 18),
    labelSmall = ui(12, FontWeight.SemiBold, 16),
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
