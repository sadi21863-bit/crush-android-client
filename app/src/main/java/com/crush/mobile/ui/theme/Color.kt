package com.crush.mobile.ui.theme

import androidx.compose.ui.graphics.Color

// Brand colors
val OpenCodePrimary = Color(0xFF6C5CE7)
val OpenCodePrimaryDark = Color(0xFF5A4BD4)
val OpenCodeSecondary = Color(0xFF00B894)
val OpenCodeAccent = Color(0xFFFD79A8)

// Light theme
val LightBackground = Color(0xFFF8F9FA)
val LightSurface = Color(0xFFFFFFFF)
val LightOnBackground = Color(0xFF1A1A2E)
val LightOnSurface = Color(0xFF2D3436)
val LightUserBubble = Color(0xFF6C5CE7)
val LightAssistantBubble = Color(0xFFF0F0F0)
val LightOnUserBubble = Color(0xFFFFFFFF)
val LightOnAssistantBubble = Color(0xFF2D3436)

// Dark theme
val DarkBackground = Color(0xFF0F0F1A)
val DarkSurface = Color(0xFF1A1A2E)
val DarkOnBackground = Color(0xFFE0E0E0)
val DarkOnSurface = Color(0xFFE0E0E0)
val DarkUserBubble = Color(0xFF6C5CE7)
val DarkAssistantBubble = Color(0xFF2D2D44)
val DarkOnUserBubble = Color(0xFFFFFFFF)
val DarkOnAssistantBubble = Color(0xFFE0E0E0)

// Semantic colors
val ErrorColor = Color(0xFFE74C3C)
val SuccessColor = Color(0xFF00B894)
val WarningColor = Color(0xFFFDCB6E)
val ToolRunningColor = Color(0xFFFDCB6E)
val ToolCompletedColor = Color(0xFF00B894)
val ToolErrorColor = Color(0xFFE74C3C)

// Diff line colors per ACTIVE theme (analysis §4 P2). The old pair was
// hardcoded light-mode material greens/reds (0xFF1B5E20 / 0xFFB71C1C) baked
// into the chat screen, so dark mode rendered the same deep greens on a
// near-black surface - the text was unreadable, and the two "tokens" were
// silently divergent from the scheme that styled everything around them.
// Light values keep the original perceptual choice; dark values go to the
// code-block-safe 300-level palette so they keep >=4.5:1 on DarkSurface.
val LightDiffAdded = Color(0xFF1B5E20)
val LightDiffRemoved = Color(0xFFB71C1C)
val DarkDiffAdded = Color(0xFFA5D6A7)
val DarkDiffRemoved = Color(0xFFEF9A9A)
