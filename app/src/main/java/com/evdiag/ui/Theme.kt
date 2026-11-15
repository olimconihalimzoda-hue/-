package com.evdiag.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val DarkColors = darkColorScheme(
    primary = Color(0xFF2EE6A6),
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF0F3D33),
    onPrimaryContainer = Color(0xFFB7F7E0),
    secondary = Color(0xFF4CC9F0),
    onSecondary = Color(0xFF00313F),
    secondaryContainer = Color(0xFF123A48),
    onSecondaryContainer = Color(0xFFC4EEFF),
    tertiary = Color(0xFFFFC857),
    background = Color(0xFF0B141A),
    onBackground = Color(0xFFE3EEF1),
    surface = Color(0xFF101D25),
    onSurface = Color(0xFFE3EEF1),
    surfaceVariant = Color(0xFF17282F),
    onSurfaceVariant = Color(0xFF9DB4BD),
    outline = Color(0xFF47616C),
    error = Color(0xFFFF6B6B),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00966B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC9F5E3),
    onPrimaryContainer = Color(0xFF00382A),
    secondary = Color(0xFF0A89B0),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3F0FA),
    onSecondaryContainer = Color(0xFF00313F),
    tertiary = Color(0xFFB07A00),
    background = Color(0xFFF2F8FA),
    onBackground = Color(0xFF0E1E25),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0E1E25),
    surfaceVariant = Color(0xFFE4EFF2),
    onSurfaceVariant = Color(0xFF4A6470),
    outline = Color(0xFF8AA3AE),
    error = Color(0xFFD64545),
)

@Composable
fun EVTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = Shapes(
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(14.dp),
            large = RoundedCornerShape(20.dp),
        ),
        content = content,
    )
}
