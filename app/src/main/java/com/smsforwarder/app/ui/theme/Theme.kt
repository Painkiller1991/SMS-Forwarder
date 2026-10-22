package com.smsforwarder.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.smsforwarder.app.data.ThemeMode

private val Green = Color(0xFF0B6E4F)
private val GreenDark = Color(0xFF084C37)
private val Sand = Color(0xFFF3F0E8)
private val Ink = Color(0xFF1A1A1A)

private val LightColors = lightColorScheme(
    primary = Green,
    onPrimary = Color.White,
    secondary = GreenDark,
    background = Sand,
    surface = Color.White,
    surfaceVariant = Color(0xFFE8E4DA),
    onBackground = Ink,
    onSurface = Ink,
    onSurfaceVariant = Color(0xFF3D3D3D)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5BE3A8),
    onPrimary = Color(0xFF003822),
    secondary = Color(0xFF7AD9B0),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    surfaceVariant = Color(0xFF2A2A2A),
    onBackground = Color(0xFFF2F2F2),
    onSurface = Color(0xFFF2F2F2),
    onSurfaceVariant = Color(0xFFC8C8C8)
)

@Composable
fun SmsForwarderTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content
    )
}
