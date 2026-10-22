package com.smsforwarder.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smsforwarder.app.data.ThemeMode

private val Teal = Color(0xFF0D7377)
private val TealBright = Color(0xFF14A3A8)
private val Mint = Color(0xFF5BE3C8)
private val SlateBg = Color(0xFF0E1416)
private val SlateSurface = Color(0xFF1A2226)
private val SlateContainer = Color(0xFF243035)
private val LightBg = Color(0xFFF2F5F6)
private val LightSurface = Color(0xFFFFFFFF)
private val LightContainer = Color(0xFFE4EBED)
private val Ink = Color(0xFF142022)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB8E8EA),
    onPrimaryContainer = Color(0xFF003738),
    secondary = Color(0xFF4A6366),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCE8EB),
    onSecondaryContainer = Color(0xFF051F22),
    tertiary = Color(0xFF526070),
    background = LightBg,
    onBackground = Ink,
    surface = LightSurface,
    onSurface = Ink,
    surfaceVariant = LightContainer,
    onSurfaceVariant = Color(0xFF3F494A),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFECF1F2),
    surfaceContainer = LightContainer,
    surfaceContainerHigh = Color(0xFFD8E0E2),
    outline = Color(0xFF6F797A),
    error = Color(0xFFBA1A1A)
)

private val DarkColors = darkColorScheme(
    primary = Mint,
    onPrimary = Color(0xFF003739),
    primaryContainer = Color(0xFF005054),
    onPrimaryContainer = Color(0xFF6FF6FA),
    secondary = Color(0xFFB1CBCF),
    onSecondary = Color(0xFF1C3437),
    secondaryContainer = Color(0xFF334B4E),
    onSecondaryContainer = Color(0xFFCDE7EB),
    tertiary = Color(0xFFB2C8DF),
    background = SlateBg,
    onBackground = Color(0xFFE1E3E3),
    surface = SlateSurface,
    onSurface = Color(0xFFE1E3E3),
    surfaceVariant = SlateContainer,
    onSurfaceVariant = Color(0xFFBFC9CA),
    surfaceContainerLowest = Color(0xFF0A1012),
    surfaceContainerLow = Color(0xFF161E21),
    surfaceContainer = SlateContainer,
    surfaceContainerHigh = Color(0xFF2E3A3E),
    outline = Color(0xFF899394),
    error = Color(0xFFFFB4AB)
)

private val AppTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    )
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp)
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
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
