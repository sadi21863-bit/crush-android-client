package com.crush.mobile.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Raw palette for the V2 token system (V2-1 design foundation, approved
 * 2026-11-19). Screens must not read these constants: read
 * [com.crush.mobile.ui.theme.CrushColors] through `LocalCrushColors`, or the
 * matching Material role. Values are measured in
 * `docs/v2/V2-1-design-foundation.md`; `CrushTokensTest` pins the contrast.
 *
 * Direction: warm near-white paper, neutral graphite accent, hue reserved for
 * status (success/warning/error). A neutral accent means colour alone can no
 * longer mark interactive text, so inline links are underlined and selection
 * carries a filled icon plus a label.
 */

// Light
val LightPaper = Color(0xFFFAF8F4)
val LightSurface = Color(0xFFFFFFFF)
val LightInk = Color(0xFF1C1B18)
val LightMuted = Color(0xFF5C5850)
val LightFaint = Color(0xFF8A857B)
val LightSeparator = Color(0xFFE5E1D8)
val LightAccent = Color(0xFF3B3B36)
val LightOnAccent = Color(0xFFFFFFFF)
val LightAccentSoft = Color(0xFFE8E5DE)
val LightSuccess = Color(0xFF1B6B4A)
val LightWarning = Color(0xFF8A5A00)
val LightError = Color(0xFFB3261E)
val LightSurfaceVariant = Color(0xFFF1EFE9)
val LightErrorContainer = Color(0xFFF7E0DE)
val LightOnErrorContainer = Color(0xFF5C1512)

// M3 container roles. Even with a mostly flat UI, Material components
// (Card, sheets, dialogs, snackbars) read these, and their defaults are the
// baseline purple-tinted neutrals, not this warm palette.
val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
val LightSurfaceContainerLow = Color(0xFFFCFAF6)
val LightSurfaceContainer = Color(0xFFF6F3EC)
val LightSurfaceContainerHigh = Color(0xFFF1EEE7)
val LightSurfaceContainerHighest = Color(0xFFEBE8E0)
val LightSurfaceDim = Color(0xFFE5E1D8)
val LightSurfaceBright = Color(0xFFFFFFFF)
val LightInverseSurface = Color(0xFF2E2E29)
val LightInverseOnSurface = Color(0xFFF4F1EA)

// Dark
val DarkBg = Color(0xFF121210)
val DarkSurface = Color(0xFF1B1B18)
val DarkInk = Color(0xFFEDEBE5)
val DarkMuted = Color(0xFFA8A49B)
val DarkFaint = Color(0xFF7E7A72)
val DarkSeparator = Color(0xFF2A2A26)
val DarkAccent = Color(0xFFD3D0C8)
val DarkOnAccent = Color(0xFF1A1A17)
val DarkAccentSoft = Color(0xFF2E2E29)
val DarkSuccess = Color(0xFF7BD3A8)
val DarkWarning = Color(0xFFF0B860)
val DarkError = Color(0xFFFF8A80)
val DarkOnError = Color(0xFF3B0A08)
val DarkSurfaceVariant = Color(0xFF232320)
val DarkErrorContainer = Color(0xFF5C1A17)
val DarkOnErrorContainer = Color(0xFFFFDAD6)

val DarkSurfaceContainerLowest = Color(0xFF0D0D0B)
val DarkSurfaceContainerLow = Color(0xFF171714)
val DarkSurfaceContainer = Color(0xFF1B1B18)
val DarkSurfaceContainerHigh = Color(0xFF232320)
val DarkSurfaceContainerHighest = Color(0xFF2C2C28)
val DarkSurfaceDim = Color(0xFF121210)
val DarkSurfaceBright = Color(0xFF3A3A35)
val DarkInverseSurface = Color(0xFFEDEBE5)
val DarkInverseOnSurface = Color(0xFF1B1B18)

// Diff line colours per ACTIVE theme. The old hardcoded pair was
// light-mode-only material green/red baked into the chat screen, so dark mode
// rendered deep greens on near-black. Both pairs below are measured against
// their own surface (7.87 / 6.57 light, 10.50 / 8.02 dark) and stay.
val LightDiffAdded = Color(0xFF1B5E20)
val LightDiffRemoved = Color(0xFFB71C1C)
val DarkDiffAdded = Color(0xFFA5D6A7)
val DarkDiffRemoved = Color(0xFFEF9A9A)

// Tints behind a diff line, so the sign is not the only signal on a wide row.
val LightDiffAddedBg = Color(0xFFE8F3EC)
val LightDiffRemovedBg = Color(0xFFFBEAEA)
val DarkDiffAddedBg = Color(0xFF1E2A22)
val DarkDiffRemovedBg = Color(0xFF2E1E1E)
