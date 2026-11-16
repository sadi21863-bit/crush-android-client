package com.crush.mobile.ui.screens.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.activity.ComponentDialog
import com.crush.mobile.data.crush.PermissionRequest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * T2B1-02 (V2 plan §6.3): the gate half of `ChatContent` - the permission
 * dialog, the question release, and composer enablement, all rendered from a
 * fabricated [ChatViewState].
 *
 * Recorded acceptance gaps (added to the 2b.1 ledger):
 * - the question dialog exposes TWO release buttons ("Deny" and "Dismiss"),
 *   where the target says exactly one release action;
 * - bubbles carry no role semantics, and their text is full-width, so
 *   position does not distinguish roles either (T2B1-01 pins the
 *   assistant-only actions row instead).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class ChatContentGateTest {

    @get:Rule
    val compose = createComposeRule()

    private val request = PermissionRequest(
        id = "perm-1",
        sessionId = "s1",
        toolCallId = "call-1",
        toolName = "bash",
        description = "List the workspace",
        action = "bash",
        path = "workspace",
    )

    private fun setContent(
        state: ChatViewState,
        actions: MutableList<ChatAction> = mutableListOf(),
    ) {
        compose.setContent {
            ChatContent(
                state = state,
                onAction = { actions += it },
                pendingTrust = null,
                onTrustConfirm = {},
                onTrustDismiss = {},
            )
        }
    }

    @Test
    fun `the permission dialog shows the request and all three decisions are enabled and tappable`() {
        setContent(ChatFixtures.view(permission = request))

        compose.onNodeWithText("Allow this action?").assertIsDisplayed()
        compose.onNodeWithText("bash").assertIsDisplayed()
        compose.onNodeWithText("List the workspace").assertIsDisplayed()
        compose.onNodeWithText("workspace").assertIsDisplayed()

        listOf("Allow once", "Always", "Deny").forEach { label ->
            compose.onNodeWithText(label)
                .assertIsDisplayed()
                .assertIsEnabled()
                .assertHeightIsAtLeast(48.dp)
        }
    }

    @Test
    fun `allow once dispatches AllowOnce with the request id`() {
        val actions = mutableListOf<ChatAction>()
        setContent(ChatFixtures.view(permission = request), actions)

        compose.onNodeWithText("Allow once").performClick()

        assertEquals(listOf(ChatAction.AllowOnce("perm-1")), actions)
    }

    @Test
    fun `always dispatches AllowForSession with the request id`() {
        val actions = mutableListOf<ChatAction>()
        setContent(ChatFixtures.view(permission = request), actions)

        compose.onNodeWithText("Always").performClick()

        assertEquals(listOf(ChatAction.AllowForSession("perm-1")), actions)
    }

    @Test
    fun `deny dispatches DenyPermission`() {
        val actions = mutableListOf<ChatAction>()
        setContent(ChatFixtures.view(permission = request), actions)

        compose.onNodeWithText("Deny").performClick()

        assertEquals(listOf(ChatAction.DenyPermission("perm-1")), actions)
    }

    @Test
    fun `dismissing the permission dialog is a deny and never an approval`() {
        val actions = mutableListOf<ChatAction>()
        setContent(ChatFixtures.view(permission = request), actions)
        compose.waitForIdle()

        // The dialog's own dismiss path (back), not a visible button.
        //
        // Compose's DialogWrapper routes back through onDismissRequest;
        // Dialog.cancel()/dismiss() only hide the window and never invoke the
        // callback, which would make this test pass vacuously only if an
        // action were fabricated. Drive the real dispatcher instead.
        val dialog = ShadowDialog.getLatestDialog() as ComponentDialog
        dialog.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()

        assertEquals(listOf(ChatAction.DenyPermission("perm-1")), actions)
    }

    @Test
    fun `the question dialog releases the run and offers no option list`() {
        val actions = mutableListOf<ChatAction>()
        setContent(
            ChatFixtures.view(question = QuestionView(id = "q1", title = "Which file should I edit?")),
            actions,
        )

        compose.onNodeWithText("Which file should I edit?").assertIsDisplayed()
        // No answer list is fabricated by this build - it can only release.
        compose.onAllNodesWithText("Option 1").assertCountEquals(0)
        compose.onAllNodesWithText("Option 2").assertCountEquals(0)

        compose.onNodeWithText("Deny").performClick()

        assertEquals(listOf(ChatAction.ReleaseQuestion), actions)
    }

    @Test
    fun `the question dialog dismiss also releases the run`() {
        val actions = mutableListOf<ChatAction>()
        setContent(
            ChatFixtures.view(question = QuestionView(id = "q1", title = "Which file should I edit?")),
            actions,
        )

        // ACCEPTANCE GAP (recorded): the target is exactly one release action;
        // today the dismiss slot is a second one. Both must release, never
        // leave the engine blocked.
        compose.onNodeWithText("Dismiss").performClick()

        assertEquals(listOf(ChatAction.ReleaseQuestion), actions)
    }

    @Test
    fun `the field follows canType and the send button follows canSend`() {
        var state by mutableStateOf(
            ChatFixtures.view(canType = false, canSend = false, draft = "hello")
        )
        compose.setContent {
            ChatContent(
                state = state,
                onAction = {},
                pendingTrust = null,
                onTrustConfirm = {},
                onTrustDismiss = {},
            )
        }

        // A disabled TextField does not publish the SetText action, so the
        // value is the stable handle: it is the field's EditableText either
        // way.
        compose.onNodeWithText("hello").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()

        state = ChatFixtures.view(canType = true, canSend = false, draft = "hello")
        compose.waitForIdle()
        compose.onNodeWithText("hello").assertIsEnabled()
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()

        state = ChatFixtures.view(canType = true, canSend = true, draft = "hello")
        compose.waitForIdle()
        compose.onNodeWithText("hello").assertIsEnabled()
        compose.onNodeWithContentDescription("Send").assertIsEnabled()
    }
}
