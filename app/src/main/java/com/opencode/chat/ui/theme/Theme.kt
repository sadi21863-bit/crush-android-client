package com.opencode.chat.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = OpenCodePrimary,
    onPrimary = DarkOnUserBubble,
    primaryContainer = OpenCodePrimaryDark,
    secondary = OpenCodeSecondary,
    tertiary = OpenCodeAccent,
    background = DarkBackground,
    surface = DarkSurface,
    onBackground = DarkOnBackground,
    onSurface = DarkOnSurface,
    error = ErrorColor,
    surfaceVariant = DarkAssistantBubble,
    onSurfaceVariant = DarkOnAssistantBubble
)

private val LightColorScheme = lightColorScheme(
    primary = OpenCodePrimary,
    onPrimary = LightOnUserBubble,
    primaryContainer = OpenCodePrimaryDark,
    secondary = OpenCodeSecondary,
    tertiary = OpenCodeAccent,
    background = LightBackground,
    surface = LightSurface,
    onBackground = LightOnBackground,
    onSurface = LightOnSurface,
    error = ErrorColor,
    surfaceVariant = LightAssistantBubble,
    onSurfaceVariant = LightOnAssistantBubble
)

@Composable
fun OpenCodeChatTheme(
    themeMode: String = "system",
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }

    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
