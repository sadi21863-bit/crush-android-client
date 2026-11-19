package com.crush.mobile.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.crush.mobile.ui.theme.CrushMobileTheme
import com.crush.mobile.ui.theme.DarkAccent
import com.crush.mobile.ui.theme.LightAccent
import com.crush.mobile.ui.theme.LocalCrushColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * V2-2 component contract: every control is reachable, at least 48dp, and a
 * read-only element never pretends to be tappable. The theme test at the
 * bottom pins that the provider actually reaches composition (a token file
 * that compiles but is never provided would pass every pure-JVM test).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h1600dp")
class CrushComponentsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun statusLabelCarriesWordsNotJustColour() {
        compose.setContent {
            CrushMobileTheme(themeMode = "light") {
                CrushStatusLabel(text = "Engine responding: yes", tone = CrushTone.Success)
            }
        }
        compose.onNodeWithText("Engine responding: yes").assertIsDisplayed()
    }

    @Test
    fun chipIsReadOnlyAndNotAnnouncedAsAnAction() {
        compose.setContent {
            CrushMobileTheme(themeMode = "light") {
                CrushChip(text = "FREE", selected = true)
            }
        }
        compose.onNodeWithText("FREE")
            .assertIsDisplayed()
            .assertHasNoClickAction()
    }

    @Test
    fun iconButtonMeetsTheTouchTargetAndFires() {
        var clicks = 0
        compose.setContent {
            CrushMobileTheme(themeMode = "light") {
                CrushIconButton(
                    icon = Icons.Default.Check,
                    contentDescription = "Confirm",
                    onClick = { clicks++ },
                )
            }
        }
        compose.onNodeWithContentDescription("Confirm")
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        compose.waitForIdle()
        assertEquals(1, clicks)
    }

    @Test
    fun primaryButtonMeetsTheTouchTarget() {
        compose.setContent {
            CrushMobileTheme(themeMode = "light") {
                CrushButton(text = "Continue", onClick = {})
            }
        }
        compose.onNodeWithText("Continue").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun emptyStateShowsItsActionAndFires() {
        var clicked = false
        compose.setContent {
            CrushMobileTheme(themeMode = "light") {
                CrushEmptyState(
                    title = "No projects yet",
                    body = "Import a folder to start.",
                    actionLabel = "Import folder",
                    onAction = { clicked = true },
                )
            }
        }
        compose.onNodeWithText("No projects yet").assertIsDisplayed()
        compose.onNodeWithText("Import folder").performClick()
        compose.waitForIdle()
        assertTrue(clicked)
    }

    @Test
    fun bannerShowsItsTextAndOptionalAction() {
        compose.setContent {
            CrushMobileTheme(themeMode = "light") {
                CrushBanner(
                    text = "A turn is running; revert is disabled.",
                    tone = CrushTone.Warning,
                    actionLabel = "Dismiss",
                    onAction = {},
                )
            }
        }
        compose.onNodeWithText("A turn is running; revert is disabled.").assertIsDisplayed()
        compose.onNodeWithText("Dismiss").assertIsDisplayed()
    }

    @Test
    fun theThemeProvidesTheGraphiteAccentInBothModes() {
        val seen = mutableMapOf("light" to emptyList<Color>(), "dark" to emptyList<Color>())
        val mode = mutableStateOf("light")
        compose.setContent {
            CrushMobileTheme(themeMode = mode.value) {
                seen[mode.value] = listOf(
                    MaterialTheme.colorScheme.primary,
                    LocalCrushColors.current.accent,
                    MaterialTheme.colorScheme.onSurface,
                    LocalCrushColors.current.ink,
                )
                CrushSectionHeader("Section")
            }
        }
        compose.onNodeWithText("Section").assertIsDisplayed()
        mode.value = "dark"
        compose.waitForIdle()

        val light = requireNotNull(seen["light"])
        val dark = requireNotNull(seen["dark"])
        assertEquals(LightAccent, light[0])
        assertEquals("Material primary and CrushColors.accent must agree", light[0], light[1])
        assertEquals("onSurface and ink must agree", light[2], light[3])
        assertEquals(DarkAccent, dark[0])
        assertEquals(dark[0], dark[1])
        assertEquals(dark[2], dark[3])
        assertTrue("the two modes must differ", light[0] != dark[0])
    }
}
