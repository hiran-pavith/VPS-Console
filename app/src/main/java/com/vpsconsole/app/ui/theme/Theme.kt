package com.vpsconsole.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Available accent colors. Each maps to a primary/secondary pair for light & dark. */
enum class AccentTheme(val label: String, val light: Long, val dark: Long, val secondaryLight: Long, val secondaryDark: Long) {
    BLUE("Ocean Blue", 0xFF0969DA, 0xFF58A6FF, 0xFF1A7F37, 0xFF3FB950),
    GREEN("Forest Green", 0xFF1A7F37, 0xFF3FB950, 0xFF0969DA, 0xFF58A6FF),
    PURPLE("Neon Purple", 0xFF8250DF, 0xFFA371F7, 0xFF0969DA, 0xFF58A6FF),
    ORANGE("Sunset Orange", 0xFFBC4C00, 0xFFF0883E, 0xFF0969DA, 0xFF58A6FF),
    RED("Crimson Red", 0xFFCF222E, 0xFFFF6B6B, 0xFF9A6700, 0xFFD29922),
    TEAL("Cyber Teal", 0xFF008C8C, 0xFF39C5CF, 0xFF8250DF, 0xFFA371F7),
    PINK("Hot Pink", 0xFFBF3989, 0xFFF778BA, 0xFF8250DF, 0xFFA371F7),
    YELLOW("Amber Gold", 0xFF9A6700, 0xFFD29922, 0xFF0969DA, 0xFF58A6FF);

    companion object {
        fun from(value: String): AccentTheme = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: BLUE
    }
}

/** Terminal color schemes. */
enum class TerminalScheme(val label: String, val foreground: Long, val background: Long) {
    DEFAULT("Default", 0xFFE6EDF3, 0xFF0B0F14),
    GREEN("Green on Black", 0xFF33FF66, 0xFF000000),
    AMBER("Amber", 0xFFFFB000, 0xFF1A1000),
    BLUE("Blue Light", 0xFF58A6FF, 0xFF04121F),
    WHITE("Light", 0xFF1F2328, 0xFFF6F8FA);

    companion object {
        fun from(value: String): TerminalScheme = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: DEFAULT
    }
}

private fun colorScheme(dark: Boolean, accent: AccentTheme) = if (dark) darkColorScheme(
    primary = Color(accent.dark),
    onPrimary = Color(0xFF04121F),
    primaryContainer = Color(accent.dark).copy(alpha = 0.18f),
    onPrimaryContainer = Color(0xFFE6EDF3),
    secondary = Color(accent.secondaryDark),
    onSecondary = Color(0xFF05210D),
    error = Color(0xFFFF6B6B),
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF121820),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF1C2530),
    onSurfaceVariant = Color(0xFF9AA7B4),
    outline = Color(0xFF334155)
) else lightColorScheme(
    primary = Color(accent.light),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(accent.light).copy(alpha = 0.12f),
    onPrimaryContainer = Color(0xFF04284D),
    secondary = Color(accent.secondaryLight),
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
fun VpsConsoleTheme(
    themeMode: String = "system",
    accentColor: String = "blue",
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = colorScheme(darkTheme, AccentTheme.from(accentColor)),
        content = content
    )
}
