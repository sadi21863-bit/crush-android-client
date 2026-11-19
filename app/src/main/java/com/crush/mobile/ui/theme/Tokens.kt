package com.crush.mobile.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Semantic colour roles (V2-1). Status roles are separate fields even where
 * they currently alias a base colour, so a future divergence does not touch
 * screens.
 *
 * Read this, never a raw `Color(0x...)`, from a composable.
 */
@Immutable
data class CrushColors(
    val paper: Color,
    val surface: Color,
    val ink: Color,
    val muted: Color,
    val faint: Color,
    val separator: Color,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    val surfaceVariant: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
) {
    val toolRunning: Color get() = warning
    val toolSucceeded: Color get() = success
    val toolFailed: Color get() = error
    val pending: Color get() = muted
    val permissionAsk: Color get() = accent
}

val LightCrushColors = CrushColors(
    paper = LightPaper,
    surface = LightSurface,
    ink = LightInk,
    muted = LightMuted,
    faint = LightFaint,
    separator = LightSeparator,
    accent = LightAccent,
    onAccent = LightOnAccent,
    accentSoft = LightAccentSoft,
    success = LightSuccess,
    warning = LightWarning,
    error = LightError,
    surfaceVariant = LightSurfaceVariant,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
)

val DarkCrushColors = CrushColors(
    paper = DarkBg,
    surface = DarkSurface,
    ink = DarkInk,
    muted = DarkMuted,
    faint = DarkFaint,
    separator = DarkSeparator,
    accent = DarkAccent,
    onAccent = DarkOnAccent,
    accentSoft = DarkAccentSoft,
    success = DarkSuccess,
    warning = DarkWarning,
    error = DarkError,
    surfaceVariant = DarkSurfaceVariant,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
)

val LocalCrushColors = staticCompositionLocalOf { LightCrushColors }

/** Spacing scale. Every gap and padding comes from here. */
object CrushSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/** Corner radii. Full-round is reserved for avatars and status dots. */
object CrushShape {
    val small = RoundedCornerShape(8.dp)
    val standard = RoundedCornerShape(12.dp)
    val large = RoundedCornerShape(16.dp)
    val sheet = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
}

/** Fixed dimensions and touch targets. */
object CrushDimens {
    val touchTarget = 48.dp
    val topBar = 56.dp
    val bottomBar = 80.dp
    val composerMin = 56.dp
    val composerMaxLines = 8
    val toolRowCollapsed = 40.dp
    val transcriptMaxWidth = 720.dp
    val sheetMaxWidth = 640.dp
    val gutter = 16.dp
    val hairline = 1.dp
}

/**
 * The type scale. Prose is [body] (15sp); a sentence never uses [metadata].
 * Code uses the platform monospace family: no bundled font, no APK growth.
 */
object CrushType {
    val title = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    )
    val section = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    )
    val body = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    )
    val secondary = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    )
    val metadata = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    )
    val code = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 20.sp,
    )
    val control = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    )

    /** Compact badge only (chips, FREE markers). Never prose. */
    val badge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
    )
}
