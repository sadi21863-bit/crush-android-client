package com.crush.mobile.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Material slots mapped onto the V2-1 scale in [CrushType]. New code should
 * read `CrushType.*`; the slots exist so existing `MaterialTheme.typography`
 * call sites move with the scale instead of being left behind.
 */
val Typography = Typography(
    displayLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
    ),
    titleLarge = CrushType.title,
    titleMedium = CrushType.section,
    titleSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = CrushType.body,
    bodyMedium = CrushType.secondary,
    bodySmall = CrushType.metadata,
    labelLarge = CrushType.control,
    labelMedium = CrushType.metadata.copy(fontWeight = FontWeight.Medium),
    labelSmall = CrushType.badge,
)
