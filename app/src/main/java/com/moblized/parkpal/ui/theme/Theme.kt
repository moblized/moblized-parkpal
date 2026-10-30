package com.moblized.parkpal.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Map marker and status colors. */
object ParkColors {
    val Free = Color(0xFF2E7D32)
    val Paid = Color(0xFF1F4FD8)
    val Check = Color(0xFFF59E0B)
    val TooLow = Color(0xFFD32F2F)
    val Parked = Color(0xFF7C3AED)
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F4FD8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE4FF),
    onPrimaryContainer = Color(0xFF00174D),
    secondary = Color(0xFF8A5A00),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDDB0),
    onSecondaryContainer = Color(0xFF2B1700),
    background = Color(0xFFF7F8FC),
    onBackground = Color(0xFF191C22),
    surface = Color(0xFFF7F8FC),
    onSurface = Color(0xFF191C22),
    surfaceVariant = Color(0xFFE3E6EF),
    onSurfaceVariant = Color(0xFF454A55),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFEEF0F6),
    surfaceContainerHigh = Color(0xFFE8EAF1),
    surfaceContainerHighest = Color(0xFFE2E5EC),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB3C5FF),
    onPrimary = Color(0xFF002A78),
    primaryContainer = Color(0xFF173EAD),
    onPrimaryContainer = Color(0xFFDCE4FF),
    secondary = Color(0xFFFFB955),
    onSecondary = Color(0xFF472A00),
    secondaryContainer = Color(0xFF654000),
    onSecondaryContainer = Color(0xFFFFDDB0),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF444852),
    onSurfaceVariant = Color(0xFFC5C6D2),
    surfaceContainerLowest = Color(0xFF0C0E13),
    surfaceContainerLow = Color(0xFF1A1C22),
    surfaceContainer = Color(0xFF1D2026),
    surfaceContainerHigh = Color(0xFF272A31),
    surfaceContainerHighest = Color(0xFF32353C),
)

private val BlackColors = DarkColors.copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0D0E11),
    surfaceContainer = Color(0xFF0E0F12),
    surfaceContainerHigh = Color(0xFF16171B),
    surfaceContainerHighest = Color(0xFF1E1F24),
)

@Composable
fun ParkPalTheme(dark: Boolean, black: Boolean, content: @Composable () -> Unit) {
    val colors = when {
        black -> BlackColors
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
