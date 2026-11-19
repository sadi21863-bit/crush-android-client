package com.crush.mobile.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the approved V2-1 token contract (V2-A8, 2026-11-19).
 *
 * These are the measurements written into `docs/v2/V2-1-design-foundation.md`
 * section 4.1. A palette edit that drops a pair below the documented floor
 * fails here rather than shipping, and the `primaryContainer` pair pins the
 * 2.05:1 user-bubble defect found while wiring the tokens.
 *
 * WCAG 2.x relative luminance, so the numbers line up with the doc and with
 * any external checker. Not an APCA audit.
 */
class CrushTokensTest {

    private fun channel(c: Float): Double {
        val v = c.toDouble()
        return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
    }

    private fun luminance(color: Color): Double =
        0.2126 * channel(color.red) +
            0.7152 * channel(color.green) +
            0.0722 * channel(color.blue)

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun assertContrast(name: String, fg: Color, bg: Color, min: Double) {
        val ratio = contrast(fg, bg)
        assertTrue(
            "$name measured %.2f, floor %.1f".format(ratio, min),
            ratio >= min
        )
    }

    // ---- text roles -------------------------------------------------------

    @Test
    fun lightTextRolesClearWcagAa() {
        val c = LightCrushColors
        listOf(c.paper, c.surface).forEach { bg ->
            assertContrast("ink", c.ink, bg, 4.5)
            assertContrast("muted", c.muted, bg, 4.5)
        }
        assertContrast("muted on surfaceVariant", c.muted, c.surfaceVariant, 4.5)
        // faint is documented as disabled/decorative only: 3:1, never prose.
        assertContrast("faint (large only)", c.faint, c.surface, 3.0)
    }

    @Test
    fun darkTextRolesClearWcagAa() {
        val c = DarkCrushColors
        listOf(c.paper, c.surface).forEach { bg ->
            assertContrast("ink", c.ink, bg, 4.5)
            assertContrast("muted", c.muted, bg, 4.5)
        }
        assertContrast("muted on surfaceVariant", c.muted, c.surfaceVariant, 4.5)
        assertContrast("faint (large only)", c.faint, c.surface, 3.0)
    }

    // ---- accent -----------------------------------------------------------

    @Test
    fun accentTextClearsAaOnEveryBaseSurface() {
        listOf(LightCrushColors, DarkCrushColors).forEach { c ->
            assertContrast("accent on paper", c.accent, c.paper, 4.5)
            assertContrast("accent on surface", c.accent, c.surface, 4.5)
            assertContrast("accent on accentSoft", c.accent, c.accentSoft, 4.5)
            assertContrast("onAccent on accent", c.onAccent, c.accent, 4.5)
        }
    }

    @Test
    fun theAccentIsNeutralGraphiteNotTheOldPurple() {
        // V2-A8: neutral accent. A purple returning here is a decision change,
        // not a palette tweak.
        assertNotEquals(Color(0xFF6C5CE7), LightCrushColors.accent)
        assertNotEquals(Color(0xFFA99BFF), DarkCrushColors.accent)
        // And the neutral accent must stay distinguishable from body text as a
        // FILL, even though it cannot be told apart by hue alone.
        assertContrast("light accent vs ink", LightCrushColors.accent, LightCrushColors.ink, 1.4)
        assertContrast("dark accent vs ink", DarkCrushColors.accent, DarkCrushColors.ink, 1.2)
    }

    // ---- status -----------------------------------------------------------

    @Test
    fun statusTextClearsAa() {
        listOf(LightCrushColors, DarkCrushColors).forEach { c ->
            listOf("success" to c.success, "warning" to c.warning, "error" to c.error)
                .forEach { (name, tone) ->
                    assertContrast("$name on paper", tone, c.paper, 4.5)
                    assertContrast("$name on surface", tone, c.surface, 4.5)
                }
        }
    }

    @Test
    fun errorContainersCarryTheirOwnText() {
        listOf(LightCrushColors, DarkCrushColors).forEach { c ->
            assertContrast(
                "onErrorContainer on errorContainer",
                c.onErrorContainer,
                c.errorContainer,
                4.5
            )
        }
    }

    // ---- diff -------------------------------------------------------------

    @Test
    fun diffLinesClearAaOnTheirSurfaceAndOnTheirTint() {
        listOf(LightCrushColors, DarkCrushColors).forEach { c ->
            val added = if (c === DarkCrushColors) DarkDiffAdded else LightDiffAdded
            val removed = if (c === DarkCrushColors) DarkDiffRemoved else LightDiffRemoved
            val addedBg = if (c === DarkCrushColors) DarkDiffAddedBg else LightDiffAddedBg
            val removedBg = if (c === DarkCrushColors) DarkDiffRemovedBg else LightDiffRemovedBg
            assertContrast("added on surface", added, c.surface, 4.5)
            assertContrast("added on own tint", added, addedBg, 4.5)
            assertContrast("removed on surface", removed, c.surface, 4.5)
            assertContrast("removed on own tint", removed, removedBg, 4.5)
        }
    }

    // ---- the defect found during V2-2 ------------------------------------

    @Test
    fun userBubbleTextIsReadableOnItsOwnFill() {
        listOf(LightCrushColors, DarkCrushColors).forEach { c ->
            assertContrast(
                "onPrimaryContainer on primaryContainer",
                c.onAccent,
                c.accent,
                4.5
            )
        }
    }

    // ---- material scheme mapping -----------------------------------------

    @Test
    fun everyRoleTheAppReadsIsSpecifiedFromThePalette() {
        listOf(false, true).forEach { dark ->
            val scheme = crushColorScheme(dark)
            val c = crushColors(dark)
            assertEquals("primary", c.accent, scheme.primary)
            assertEquals("onPrimary", c.onAccent, scheme.onPrimary)
            assertEquals("primaryContainer", c.accent, scheme.primaryContainer)
            assertEquals("onPrimaryContainer", c.onAccent, scheme.onPrimaryContainer)
            assertEquals("background", c.paper, scheme.background)
            assertEquals("onBackground", c.ink, scheme.onBackground)
            assertEquals("surface", c.surface, scheme.surface)
            assertEquals("onSurface", c.ink, scheme.onSurface)
            assertEquals("surfaceVariant", c.surfaceVariant, scheme.surfaceVariant)
            assertEquals("onSurfaceVariant", c.muted, scheme.onSurfaceVariant)
            assertEquals("secondaryContainer", c.accentSoft, scheme.secondaryContainer)
            assertEquals("onSecondaryContainer", c.ink, scheme.onSecondaryContainer)
            assertEquals("error", c.error, scheme.error)
            assertEquals("errorContainer", c.errorContainer, scheme.errorContainer)
            assertEquals("onErrorContainer", c.onErrorContainer, scheme.onErrorContainer)
            assertEquals("outline", c.faint, scheme.outline)
            assertEquals("outlineVariant", c.separator, scheme.outlineVariant)
            // tertiary used to be the 2.48:1 pink. It is now a neutral text
            // role, and nothing may use it for status.
            assertEquals("tertiary", c.muted, scheme.tertiary)
        }
    }

    @Test
    fun tertiaryAsTextClearsAa() {
        listOf(false, true).forEach { dark ->
            val scheme = crushColorScheme(dark)
            assertContrast("tertiary on background", scheme.tertiary, scheme.background, 4.5)
        }
    }

    // ---- type, spacing, dimensions ---------------------------------------

    @Test
    fun theTypeScaleMatchesTheApprovedSizes() {
        assertEquals(15f, CrushType.body.fontSize.value, 0f)
        assertEquals(22f, CrushType.body.lineHeight.value, 0f)
        assertEquals(12f, CrushType.metadata.fontSize.value, 0f)
        assertEquals(13f, CrushType.secondary.fontSize.value, 0f)
        assertEquals(13f, CrushType.code.fontSize.value, 0f)
        assertEquals(FontFamily.Monospace, CrushType.code.fontFamily)
        assertEquals(FontWeight.Medium, CrushType.control.fontWeight)
        assertEquals(16f, CrushType.section.fontSize.value, 0f)
        assertEquals(22f, CrushType.title.fontSize.value, 0f)
        // Prose must stay larger than metadata, or Q2's answer is cosmetic.
        assertTrue(
            "body %.0fsp must exceed metadata %.0fsp".format(
                CrushType.body.fontSize.value,
                CrushType.metadata.fontSize.value
            ),
            CrushType.body.fontSize.value > CrushType.metadata.fontSize.value
        )
    }

    @Test
    fun materialSlotsUseTheSameScale() {
        assertEquals(CrushType.body, Typography.bodyLarge)
        assertEquals(CrushType.secondary, Typography.bodyMedium)
        assertEquals(CrushType.metadata, Typography.bodySmall)
        assertEquals(CrushType.control, Typography.labelLarge)
        assertEquals(CrushType.section, Typography.titleMedium)
        assertEquals(CrushType.title, Typography.titleLarge)
    }

    @Test
    fun touchTargetsMeetTheDocumentedMinimums() {
        assertTrue("touch target", CrushDimens.touchTarget.value >= 48f)
        assertEquals(56f, CrushDimens.topBar.value, 0f)
        assertEquals(80f, CrushDimens.bottomBar.value, 0f)
        assertEquals(56f, CrushDimens.composerMin.value, 0f)
        assertEquals(16f, CrushDimens.gutter.value, 0f)
        assertTrue("composer lines", CrushDimens.composerMaxLines >= 4)
        // Spacing must be a monotone scale, not four names for 16dp.
        val steps = listOf(
            CrushSpacing.xs, CrushSpacing.sm, CrushSpacing.md,
            CrushSpacing.lg, CrushSpacing.xl, CrushSpacing.xxl
        )
        steps.zipWithNext().forEach { (a, b) ->
            assertTrue("spacing $a -> $b", b > a)
        }
    }

    @Test
    fun diffColorsFollowTheCompiledThemeNotTheSystemTheme() {
        val light = crushDiffColors(false)
        assertEquals(LightDiffAdded, light.added)
        assertEquals(LightDiffRemoved, light.removed)
        val dark = crushDiffColors(true)
        assertEquals(DarkDiffAdded, dark.added)
        assertEquals(DarkDiffRemoved, dark.removed)
    }
}
