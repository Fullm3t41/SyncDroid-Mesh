package com.syncdeck.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.syncdeck.app.model.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF111111),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7F6F1),
    onPrimaryContainer = Color(0xFF075E4B),
    secondary = Color(0xFF10A37F),
    onSecondary = Color.White,
    background = Color(0xFFF7F7F5),
    onBackground = Color(0xFF181817),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF181817),
    surfaceVariant = Color(0xFFF0F0ED),
    onSurfaceVariant = Color(0xFF686864),
    outline = Color(0xFFD9D9D4),
    outlineVariant = Color(0xFFE7E7E2),
    error = Color(0xFFB42318),
    errorContainer = Color(0xFFFFEAE7),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF4F4F1),
    onPrimary = Color(0xFF171716),
    primaryContainer = Color(0xFF163B32),
    onPrimaryContainer = Color(0xFFA5E8D5),
    secondary = Color(0xFF5ED1B4),
    onSecondary = Color(0xFF07382D),
    background = Color(0xFF161615),
    onBackground = Color(0xFFF2F2EE),
    surface = Color(0xFF20201F),
    onSurface = Color(0xFFF2F2EE),
    surfaceVariant = Color(0xFF292928),
    onSurfaceVariant = Color(0xFFB5B5AF),
    outline = Color(0xFF3D3D3A),
    outlineVariant = Color(0xFF30302E),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF5B1915),
)

private val SyncDeckTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.6).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 21.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
)

@Composable
fun SyncDeckTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = SyncDeckTypography,
        content = content,
    )
}
