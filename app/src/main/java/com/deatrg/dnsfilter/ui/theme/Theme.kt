package com.deatrg.dnsfilter.ui.theme

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

private val DarkColorScheme = darkColorScheme(
    primary = SignalGreenLight,
    onPrimary = Color(0xFF003829),
    primaryContainer = SignalGreenContainerDark,
    onPrimaryContainer = Color(0xFFB5F1D8),
    secondary = Color(0xFFB7C9C0),
    onSecondary = Color(0xFF24352E),
    secondaryContainer = Color(0xFF30453B),
    onSecondaryContainer = Color(0xFFD3E8DD),
    tertiary = SignalGreenLight,
    onTertiary = Color(0xFF003829),
    tertiaryContainer = SignalGreenContainerDark,
    onTertiaryContainer = Color(0xFFB5F1D8),
    error = Color(0xFFFFB4AF),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF8E1718),
    onErrorContainer = Color(0xFFFFDAD7),
    background = Night,
    onBackground = NightText,
    surface = NightSurface,
    onSurface = NightText,
    surfaceVariant = NightRaised,
    onSurfaceVariant = Color(0xFFBFC9C2),
    outline = Color(0xFF89938D),
    outlineVariant = Color(0xFF3F4943),
    inverseSurface = Color(0xFFE1E7E2),
    inverseOnSurface = Color(0xFF2D322E),
    inversePrimary = SignalGreen
)

private val LightColorScheme = lightColorScheme(
    primary = SignalGreen,
    onPrimary = Color.White,
    primaryContainer = SignalGreenContainer,
    onPrimaryContainer = Color(0xFF0E3A2E),
    secondary = Color(0xFF4E6359),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD1E8DC),
    onSecondaryContainer = Color(0xFF0C1F17),
    tertiary = SignalGreen,
    onTertiary = Color.White,
    tertiaryContainer = SignalGreenContainer,
    onTertiaryContainer = Color(0xFF0E3A2E),
    error = Brick,
    onError = Color.White,
    errorContainer = BrickLight,
    onErrorContainer = Color(0xFF410002),
    background = Paper,
    onBackground = Ink,
    surface = PaperSurface,
    onSurface = Ink,
    surfaceVariant = PaperRaised,
    onSurfaceVariant = InkMuted,
    outline = Color(0xFF737D77),
    outlineVariant = Color(0xFFD5DBD6),
    inverseSurface = Color(0xFF2D322E),
    inverseOnSurface = Color(0xFFF0F2EE),
    inversePrimary = SignalGreenLight
)

private val AppTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        lineHeight = 44.sp,
        letterSpacing = (-1.2).sp
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.8).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 31.sp,
        letterSpacing = (-0.4).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.7.sp)
)

@Composable
fun DnsFilterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = AppTypography,
        content = content
    )
}
