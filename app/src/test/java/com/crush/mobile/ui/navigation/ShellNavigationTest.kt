package com.crush.mobile.ui.navigation

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.crush.mobile.ui.theme.CrushMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * V2-3: the shell is the only primary-navigation surface.
 *
 * Stub destinations keep this test about navigation, not about the screens
 * (which have their own suites). The back-stack assertions are the point:
 * Chat is always the root, at most one secondary destination sits above it,
 * and repeated bar taps cannot produce duplicates.
 */
@RunWith(RobolectricTestRunner::class)
class ShellNavigationTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var nav: NavHostController

    private fun setContent(
        start: String = "chat",
        codeActive: Boolean = false,
        onSelectChat: (() -> Boolean)? = null,
    ) {
        compose.setContent {
            nav = rememberNavController()
            CrushMobileTheme(themeMode = "light") {
                CrushShell(
                    navController = nav,
                    fallbackRoute = start,
                    codeActive = codeActive,
                    onSelectChat = onSelectChat,
                ) {
                    NavHost(navController = nav, startDestination = start) {
                        composable("chat") { StubDestination("chat") }
                        composable("projects") { StubDestination("projects") }
                        composable("settings") { StubDestination("settings") }
                        composable("onboarding") { StubDestination("onboarding") }
                        composable("review") { StubDestination("review") }
                    }
                }
            }
        }
    }

    @Composable
    private fun StubDestination(route: String) {
        Text("$route-content")
    }

    @Test
    @Config(qualifiers = "w411dp-h1600dp")
    fun `a project chat highlights Crush Code and Chat resets the binding`() {
        var chatSelections = 0
        setContent(
            start = "chat",
            codeActive = true,
            onSelectChat = {
                chatSelections += 1
                true
            },
        )

        compose.onNodeWithText("Crush Code").assertIsSelected()
        compose.onNodeWithText("Crush Chat").assertIsNotSelected()

        compose.onNodeWithText("Crush Chat").performClick()
        compose.waitForIdle()

        assertEquals(1, chatSelections)
    }

    @Test
    @Config(qualifiers = "w411dp-h1600dp")
    fun `a running project vetoes the Chat switch`() {
        setContent(
            start = "projects",
            codeActive = true,
            onSelectChat = { false },
        )

        compose.onNodeWithText("Crush Chat").performClick()
        compose.waitForIdle()

        assertEquals("projects", nav.currentDestination?.route)
    }

    @Test
    @Config(qualifiers = "w411dp-h1600dp")
    fun `phone shows the bottom bar and opens each destination`() {
        setContent()
        compose.onNodeWithTag("shell-bottom-bar").assertIsDisplayed()

        compose.onNodeWithText("Crush Code").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("projects-content").assertIsDisplayed()
        assertEquals("projects", nav.currentDestination?.route)

        compose.onNodeWithText("Settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("settings-content").assertIsDisplayed()
        assertEquals("settings", nav.currentDestination?.route)
    }

    @Test
    @Config(qualifiers = "w411dp-h1600dp")
    fun `switching destinations never duplicates and back returns to chat`() {
        setContent()

        compose.onNodeWithText("Crush Code").performClick()
        compose.waitForIdle()
        // Chat stays at the root: back from a secondary destination lands there.
        assertEquals("chat", nav.previousBackStackEntry?.destination?.route)

        compose.onNodeWithText("Settings").performClick()
        compose.waitForIdle()
        // Projects was replaced, not stacked: Settings' parent is still Chat.
        assertEquals("chat", nav.previousBackStackEntry?.destination?.route)

        compose.onNodeWithText("Crush Chat").performClick()
        compose.waitForIdle()
        assertEquals("chat", nav.currentDestination?.route)
        // Nothing is left to pop: no duplicate chat entry was pushed.
        assertFalse(nav.popBackStack())
        assertNull(nav.previousBackStackEntry)
    }

    @Test
    @Config(qualifiers = "w411dp-h1600dp")
    fun `the chat entry survives a detour so a live run is not torn down`() {
        setContent()
        val chatEntry = nav.currentBackStackEntry
        assertNotNull(chatEntry)

        compose.onNodeWithText("Settings").performClick()
        compose.waitForIdle()

        // Same NavBackStackEntry id, not merely the same route: the chat
        // ViewModelStore (and the live run inside it) is scoped to that entry,
        // so a detour to Settings must not recreate the conversation.
        assertEquals(chatEntry!!.id, nav.previousBackStackEntry?.id)

        compose.onNodeWithText("Crush Chat").performClick()
        compose.waitForIdle()
        assertEquals(chatEntry.id, nav.currentBackStackEntry?.id)
    }

    @Test
    @Config(qualifiers = "w411dp-h1600dp")
    fun `rapid destination taps leave exactly one entry above chat`() {
        setContent()

        compose.onNodeWithText("Crush Code").performClick()
        // Deliberately no waitForIdle between the taps: a stale callback that
        // navigated after the shell recomposed would stack two destinations.
        compose.onNodeWithText("Settings").performClick()
        compose.waitForIdle()

        assertEquals("settings", nav.currentDestination?.route)
        assertEquals("chat", nav.previousBackStackEntry?.destination?.route)
    }

    @Test
    @Config(qualifiers = "w411dp-h1600dp")
    fun `back from settings returns to the conversation`() {
        setContent()
        compose.onNodeWithText("Settings").performClick()
        compose.waitForIdle()

        compose.runOnUiThread { nav.popBackStack() }
        compose.waitForIdle()

        compose.onNodeWithText("chat-content").assertIsDisplayed()
        assertEquals("chat", nav.currentDestination?.route)
    }

    @Test
    @Config(qualifiers = "w411dp-h1600dp")
    fun `non-primary routes render without shell chrome`() {
        setContent(start = "review")
        compose.onNodeWithText("review-content").assertIsDisplayed()
        compose.onNodeWithTag("shell-bottom-bar").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w800dp-h1200dp")
    fun `rail replaces the bottom bar from 600dp`() {
        setContent()
        compose.onNodeWithTag("shell-rail").assertIsDisplayed()
        compose.onNodeWithTag("shell-bottom-bar").assertDoesNotExist()

        compose.onNodeWithText("Crush Code").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("projects-content").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1000dp-h1400dp")
    fun `extended rail from 840dp`() {
        setContent()
        compose.onNodeWithTag("shell-extended-rail").assertIsDisplayed()
        compose.onNodeWithTag("shell-bottom-bar").assertDoesNotExist()

        compose.onNodeWithText("Settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("settings-content").assertIsDisplayed()
    }
}
