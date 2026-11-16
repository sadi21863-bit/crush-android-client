package com.crush.mobile.ui.screens.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2B1-04 (V2 plan Appendix C.2): the host's draft-callback freshness rows.
 *
 * The point of the wrapper is that content captures ONE lambda, and that
 * lambda decides with the CURRENT `canType`/`prewarm` through
 * `rememberUpdatedState`. Each row therefore captures the callback, recomposes
 * with different values, and only THEN invokes the captured instance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class ChatHostCallbackTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `captured callback sees a later canType false and does nothing`() {
        var canType by mutableStateOf(true)
        var prewarm by mutableStateOf(false)
        var starts = 0
        val actions = mutableListOf<ChatAction>()
        lateinit var captured: (String) -> Unit

        compose.setContent {
            captured = rememberDraftCallback(
                canType = canType,
                prewarm = prewarm,
                startEngine = { starts += 1 },
                send = { actions += it },
            )
        }
        val firstInstance = captured

        canType = false
        compose.waitForIdle()
        // Same lambda instance: freshness does not come from recreating it.
        assertSame(firstInstance, captured)

        compose.runOnIdle { captured("hello") }

        assertEquals(0, starts)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `captured callback sees a later prewarm false and starts once then dispatches once`() {
        var canType by mutableStateOf(true)
        var prewarm by mutableStateOf(true)
        var starts = 0
        val actions = mutableListOf<ChatAction>()
        lateinit var captured: (String) -> Unit

        compose.setContent {
            captured = rememberDraftCallback(
                canType = canType,
                prewarm = prewarm,
                startEngine = { starts += 1 },
                send = { actions += it },
            )
        }

        prewarm = false
        compose.waitForIdle()

        compose.runOnIdle { captured("hello") }

        assertEquals(1, starts)
        assertEquals(listOf(ChatAction.DraftChanged("hello")), actions)
    }

    @Test
    fun `captured callback sees a later canType true and starts once then dispatches once`() {
        var canType by mutableStateOf(false)
        var prewarm by mutableStateOf(false)
        var starts = 0
        val actions = mutableListOf<ChatAction>()
        lateinit var captured: (String) -> Unit

        compose.setContent {
            captured = rememberDraftCallback(
                canType = canType,
                prewarm = prewarm,
                startEngine = { starts += 1 },
                send = { actions += it },
            )
        }

        canType = true
        compose.waitForIdle()

        compose.runOnIdle { captured("hello") }

        assertEquals(1, starts)
        assertEquals(listOf(ChatAction.DraftChanged("hello")), actions)
    }

    @Test
    fun `when both values change the latest pair decides in both directions`() {
        var canType by mutableStateOf(true)
        var prewarm by mutableStateOf(false)
        var starts = 0
        val actions = mutableListOf<ChatAction>()
        lateinit var captured: (String) -> Unit

        compose.setContent {
            captured = rememberDraftCallback(
                canType = canType,
                prewarm = prewarm,
                startEngine = { starts += 1 },
                send = { actions += it },
            )
        }

        // Latest canType=false must win over the captured true.
        canType = false
        prewarm = true
        compose.waitForIdle()
        compose.runOnIdle { captured("blocked") }
        assertEquals(0, starts)
        assertTrue(actions.isEmpty())

        // Latest canType=true + prewarm=true must win over BOTH captured
        // values: no start (already warm), one dispatch.
        canType = true
        prewarm = true
        compose.waitForIdle()
        compose.runOnIdle { captured("accepted") }

        assertEquals(0, starts)
        assertEquals(listOf(ChatAction.DraftChanged("accepted")), actions)
    }
}
