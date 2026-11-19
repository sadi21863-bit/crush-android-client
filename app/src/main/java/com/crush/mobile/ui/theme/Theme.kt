package com.crush.mobile.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/**
 * Themed diff tokens. Material's scheme has no "added/removed line" slot, so
 * these follow the COMPILED theme decision (the themeMode setting can force
 * what isSystemInDarkTheme would deny) instead of the system one.
 */
data class DiffColors(
    val added: Color,
    val removed: Color,
    val addedBg: Color,
    val removedBg: Color,
)

val LocalDiffColors = staticCompositionLocalOf {
    DiffColors(
        added = LightDiffAdded,
        removed = LightDiffRemoved,
        addedBg = LightDiffAddedBg,
        removedBg = LightDiffRemovedBg,
    )
}

private val CrushShapes = Shapes(
    extraSmall = CrushShape.small,
    small = CrushShape.small,
    medium = CrushShape.standard,
    large = CrushShape.large,
    extraLarge = RoundedCornerShape(20.dp),
)

private val LightColorScheme = lightColorScheme(
    primary = LightAccent,
    onPrimary = LightOnAccent,
    primaryContainer = LightAccent,
    onPrimaryContainer = LightOnAccent,
    inversePrimary = DarkAccent,
    secondary = LightMuted,
    onSecondary = LightSurface,
    secondaryContainer = LightAccentSoft,
    onSecondaryContainer = LightInk,
    tertiary = LightMuted,
    onTertiary = LightSurface,
    tertiaryContainer = LightAccentSoft,
    onTertiaryContainer = LightInk,
    background = LightPaper,
    onBackground = LightInk,
    surface = LightSurface,
    onSurface = LightInk,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightMuted,
    surfaceTint = LightAccent,
    inverseSurface = LightInverseSurface,
    inverseOnSurface = LightInverseOnSurface,
    error = LightError,
    onError = Color.White,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
    outline = LightFaint,
    outlineVariant = LightSeparator,
    scrim = Color.Black,
    surfaceBright = LightSurfaceBright,
    surfaceDim = LightSurfaceDim,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainerLowest = LightSurfaceContainerLowest,
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkAccent,
    onPrimary = DarkOnAccent,
    primaryContainer = DarkAccent,
    onPrimaryContainer = DarkOnAccent,
    inversePrimary = LightAccent,
    secondary = DarkMuted,
    onSecondary = DarkBg,
    secondaryContainer = DarkAccentSoft,
    onSecondaryContainer = DarkInk,
    tertiary = DarkMuted,
    onTertiary = DarkBg,
    tertiaryContainer = DarkAccentSoft,
    onTertiaryContainer = DarkInk,
    background = DarkBg,
    onBackground = DarkInk,
    surface = DarkSurface,
    onSurface = DarkInk,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkMuted,
    surfaceTint = DarkAccent,
    inverseSurface = DarkInverseSurface,
    inverseOnSurface = DarkInverseOnSurface,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
    outline = DarkFaint,
    outlineVariant = DarkSeparator,
    scrim = Color.Black,
    surfaceBright = DarkSurfaceBright,
    surfaceDim = DarkSurfaceDim,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainerLowest = DarkSurfaceContainerLowest,
)

/**
 * The compiled scheme for a dark/light decision, exposed for tests that pin
 * every role the app reads. Screens must not call this; they read
 * `MaterialTheme.colorScheme` or [LocalCrushColors].
 */
internal fun crushColorScheme(dark: Boolean): ColorScheme =
    if (dark) DarkColorScheme else LightColorScheme

internal fun crushColors(dark: Boolean): CrushColors =
    if (dark) DarkCrushColors else LightCrushColors

internal fun crushDiffColors(dark: Boolean): DiffColors = if (dark) {
    DiffColors(DarkDiffAdded, DarkDiffRemoved, DarkDiffAddedBg, DarkDiffRemovedBg)
} else {
    DiffColors(LightDiffAdded, LightDiffRemoved, LightDiffAddedBg, LightDiffRemovedBg)
}

@Composable
fun CrushMobileTheme(
    themeMode: String = "system",
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }

    val scheme = crushColorScheme(darkTheme)
    val crushColors = crushColors(darkTheme)
    val diffColors = crushDiffColors(darkTheme)

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = scheme.background.toArgb()
            // Device evidence 2026-11-20: with only the status bar themed, the
            // system navigation bar stayed white in dark mode - a bright strip
            // under a near-black app. Colour and icon appearance must travel
            // together, or dark mode gets dark icons on a dark bar.
            window.navigationBarColor = scheme.background.toArgb()
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    CompositionLocalProvider(
        LocalCrushColors provides crushColors,
        LocalDiffColors provides diffColors,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = Typography,
            shapes = CrushShapes,
            content = content,
        )
    }
}
