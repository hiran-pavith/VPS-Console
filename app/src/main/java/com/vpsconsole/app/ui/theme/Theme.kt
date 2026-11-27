package com.vpsconsole.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF58A6FF),
    onPrimary = Color(0xFF04121F),
    primaryContainer = Color(0xFF173A5E),
    onPrimaryContainer = Color(0xFFD2E7FF),
    secondary = Color(0xFF3FB950),
    onSecondary = Color(0xFF05210D),
    error = Color(0xFFFF6B6B),
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF121820),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF1C2530),
    onSurfaceVariant = Color(0xFF9AA7B4),
    outline = Color(0xFF334155)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0969DA),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD2E7FF),
    onPrimaryContainer = Color(0xFF04284D),
    secondary = Color(0xFF1A7F37),
    onSecondary = Color(0xFFFFFFFF),
    error = Color(0xFFCF222E),
    background = Color(0xFFF6F8FA),
    onBackground = Color(0xFF1F2328),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1F2328),
    surfaceVariant = Color(0xFFEAEFF4),
    onSurfaceVariant = Color(0xFF57606A),
    outline = Color(0xFFD0D7DE)
)

@Composable
fun VpsConsoleTheme(themeMode: String = "system", content: @Composable () -> Unit) {
    val darkTheme = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
