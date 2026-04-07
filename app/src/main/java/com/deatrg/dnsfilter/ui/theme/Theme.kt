package com.deatrg.dnsfilter.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Blue80,
    onPrimary = Blue40,
    secondary = BlueGrey80,
    onSecondary = BlueGrey40,
    tertiary = Green80,
    error = Red80
)

private val LightColorScheme = lightColorScheme(
    primary = Blue40,
    onPrimary = SurfaceLight,
    secondary = BlueGrey40,
    onSecondary = SurfaceLight,
    tertiary = Green40,
    error = Red40
)

@Composable
fun DnsFilterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
