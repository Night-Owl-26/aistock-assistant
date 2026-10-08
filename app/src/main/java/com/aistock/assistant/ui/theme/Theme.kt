package com.aistock.assistant.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

private val Light = lightColorScheme(
    primary = Color(0xFF0B6B5B), onPrimary = Color.White,
    primaryContainer = Color(0xFFBFEFE3), onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFF4A635C), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD5EAE3), onSecondaryContainer = Color(0xFF06201A),
    tertiary = Color(0xFF8A5A00), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDDB0), onTertiaryContainer = Color(0xFF2C1A00),
    error = Color(0xFFB3261E), onError = Color.White,
    errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFF6F8F7), onBackground = Color(0xFF171D1B),
    surface = Color(0xFFF6F8F7), onSurface = Color(0xFF171D1B),
    surfaceVariant = Color(0xFFE3EAE7), onSurfaceVariant = Color(0xFF434C49),
    outline = Color(0xFF727C78), outlineVariant = Color(0xFFC6CFCB),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFEEF2F0), surfaceContainerHigh = Color(0xFFE8EDEA), surfaceContainerHighest = Color(0xFFE2E8E5),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF7FD8C4), onPrimary = Color(0xFF00382E),
    primaryContainer = Color(0xFF005144), onPrimaryContainer = Color(0xFFBFEFE3),
    secondary = Color(0xFFB1CCC4), onSecondary = Color(0xFF1C352F),
    secondaryContainer = Color(0xFF2B4640), onSecondaryContainer = Color(0xFFCCE8DF),
    tertiary = Color(0xFFFFC857), onTertiary = Color(0xFF452B00),
    tertiaryContainer = Color(0xFF634000), onTertiaryContainer = Color(0xFFFFDDB0),
    error = Color(0xFFF2B8B5), onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18), onErrorContainer = Color(0xFFF9DEDC),
    background = Color(0xFF0F1413), onBackground = Color(0xFFDEE4E1),
    surface = Color(0xFF0F1413), onSurface = Color(0xFFDEE4E1),
    surfaceVariant = Color(0xFF3F4946), onSurfaceVariant = Color(0xFFBEC9C5),
    outline = Color(0xFF89938F), outlineVariant = Color(0xFF3F4946),
    surfaceContainerLowest = Color(0xFF0A0F0E), surfaceContainerLow = Color(0xFF181E1C),
    surfaceContainer = Color(0xFF1B2220), surfaceContainerHigh = Color(0xFF252C2A), surfaceContainerHighest = Color(0xFF303735),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun AIStockTheme(mode: String, content: @Composable () -> Unit) {
    val dark = when (mode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val base = Typography()
    val typography = base.copy(
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontSize = 15.sp),
    )
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = typography, shapes = AppShapes, content = content)
}
