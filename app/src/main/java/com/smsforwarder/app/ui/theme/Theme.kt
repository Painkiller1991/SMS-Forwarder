package com.smsforwarder.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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
    onBackground = Ink,
    onSurface = Ink
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF3DDC97),
    onPrimary = Color.Black,
    secondary = Color(0xFF2BB673),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E)
)

@Composable
fun SmsForwarderTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content
    )
}
