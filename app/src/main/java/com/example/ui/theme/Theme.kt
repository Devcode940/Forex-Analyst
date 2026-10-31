package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ForexDarkColorScheme = darkColorScheme(
    primary = PrimaryCyan,
    secondary = BullishGreen,
    tertiary = WarningGold,
    background = DarkBackground,
    surface = SurfaceDark,
    onPrimary = Color.Black,
    onSecondary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White,
    surfaceVariant = SurfaceContainerDark,
    onSurfaceVariant = Color.White,
    outline = BorderOutline,
    outlineVariant = BorderOutline
)

private val ForexLightColorScheme = lightColorScheme(
    primary = PrimaryCyan,
    secondary = BullishGreen,
    tertiary = WarningGold,
    background = LightBackground,
    surface = SurfaceLight,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = TextDark,
    onSurface = TextDark,
    surfaceVariant = SurfaceContainerLight,
    onSurfaceVariant = TextDark,
    outline = BorderOutlineLight,
    outlineVariant = BorderOutlineLight
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) ForexDarkColorScheme else ForexLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
