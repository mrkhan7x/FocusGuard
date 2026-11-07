package com.focusguard.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9DB4FF),
    onPrimary = Color(0xFF0B1230),
    primaryContainer = Color(0xFF26345F),
    onPrimaryContainer = Color(0xFFDCE4FF),
    secondary = Color(0xFF7FD6C2),
    onSecondary = Color(0xFF00201A),
    secondaryContainer = Color(0xFF1F3B36),
    onSecondaryContainer = Color(0xFFCDEFE6),
    background = Color(0xFF0E1117),
    onBackground = Color(0xFFE6E9F0),
    surface = Color(0xFF0E1117),
    onSurface = Color(0xFFE6E9F0),
    surfaceVariant = Color(0xFF1A202B),
    onSurfaceVariant = Color(0xFFA3ACBF),
    surfaceContainer = Color(0xFF151A23),
    surfaceContainerHigh = Color(0xFF1A202B),
    outline = Color(0xFF3A4254),
    outlineVariant = Color(0xFF2A3140),
    error = Color(0xFFFF8A8A),
    onError = Color(0xFF3B0A0A),
    errorContainer = Color(0xFF4A1F1F),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF3B5BDB),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDDE4FF),
    onPrimaryContainer = Color(0xFF0B1A5C),
    secondary = Color(0xFF127A68),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCDEFE6),
    onSecondaryContainer = Color(0xFF00201A),
    background = Color(0xFFF6F7FB),
    onBackground = Color(0xFF151822),
    surface = Color(0xFFF6F7FB),
    onSurface = Color(0xFF151822),
    surfaceVariant = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFF5A6275),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEBEEF7),
    outline = Color(0xFFB8BFD0),
    outlineVariant = Color(0xFFDDE1EC),
    error = Color(0xFFC62828),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

@Composable
fun FocusGuardTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
