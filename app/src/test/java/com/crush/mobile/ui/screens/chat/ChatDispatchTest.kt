package com.crush.mobile.ui.screens.chat

import com.crush.mobile.project.ProjectRef
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T2A-05 (V2 plan §5.4.2, Appendix C.2/C.3): the pure draft gate, the
 * permission mapping, and the host-effect partition.
 */
class ChatDispatchTest {

    @Test
    fun `a disabled composer dispatches nothing and starts nothing`() {
        var starts = 0
        var sent: ChatAction? = null

        val accepted = ChatDispatch.draft(
            text = "blocked",
            canType = false,
            prewarm = false,
            startEngine = { starts++ },
            send = { sent = it },
        )

        assertFalse(accepted)
        assertEquals(0, starts)
        assertNull(sent)
    }

    @Test
    fun `prewarm off starts the engine once and dispatches once`() {
        var starts = 0
        var sent: ChatAction? = null

        val accepted = ChatDispatch.draft(
            text = "hello",
            canType = true,
            prewarm = false,
            startEngine = { starts++ },
            send = { sent = it },
        )

        assertTrue(accepted)
        assertEquals(1, starts)
        assertEquals(ChatAction.DraftChanged("hello"), sent)
    }

    @Test
    fun `prewarm on dispatches without a redundant start`() {
        var starts = 0
        var sent: ChatAction? = null

        val accepted = ChatDispatch.draft(
            text = "hello",
            canType = true,
            prewarm = true,
            startEngine = { starts++ },
            send = { sent = it },
        )

        assertTrue(accepted)
        assertEquals(0, starts)
        assertEquals(ChatAction.DraftChanged("hello"), sent)
    }

    @Test
    fun `the three permission actions map to exactly three decisions`() {
        assertEquals(
            PermissionDecision(allow = true, forSession = false),
            ChatDispatch.permissionDecision(ChatAction.AllowOnce("p1")),
        )
        assertEquals(
            PermissionDecision(allow = true, forSession = true),
            ChatDispatch.permissionDecision(ChatAction.AllowForSession("p1")),
        )
        assertEquals(
            PermissionDecision(allow = false, forSession = false),
            ChatDispatch.permissionDecision(ChatAction.DenyPermission("p1")),
        )
        assertNull(ChatDispatch.permissionDecision(ChatAction.Send))
    }

    @Test
    fun `StartEngine is ViewModel-owned and never a permission decision`() {
        assertNull(ChatDispatch.hostEffect(ChatAction.StartEngine))
        assertNull(ChatDispatch.permissionDecision(ChatAction.StartEngine))
    }

    @Test
    fun `the review action returns its explicit destination`() {
        assertEquals(
            ChatHostEffect.Navigate(HostDestination.Review),
            ChatDispatch.hostEffect(ChatAction.OpenReview),
        )
    }

    @Test
    fun `handoff and trust actions return explicit host effects`() {
        assertEquals(
            ChatHostEffect.ConfirmHandoff(summary = "the plan", destinationId = "dest"),
            ChatDispatch.hostEffect(ChatAction.ConfirmHandoff(destinationId = "dest", summary = "the plan")),
        )
        assertEquals(
            ChatHostEffect.OpenHandoffInCode,
            ChatDispatch.hostEffect(ChatAction.OpenHandoffInCode),
        )

        val ref = ProjectRef(
            id = UUID.randomUUID(),
            label = "Project",
            repoDir = File("build/test-tmp/project"),
        )
        assertEquals(
            ChatHostEffect.GrantTrust(ref),
            ChatDispatch.hostEffect(ChatAction.GrantHandoffTrust(ref)),
        )
    }

    @Test
    fun `ViewModel-owned actions have no host effect and no permission decision`() {
        val viewModelOwned = listOf(
            ChatAction.DraftChanged("x"),
            ChatAction.Send,
            ChatAction.Stop,
            ChatAction.NewChat,
            ChatAction.OpenSessions,
            ChatAction.OpenModels,
            ChatAction.SelectModel("m"),
            ChatAction.ReloadModels,
            ChatAction.SelectSession("s"),
            ChatAction.DeleteSession("s"),
            ChatAction.ReleaseQuestion,
            ChatAction.Retry("x"),
            ChatAction.UsePlanInCode(1),
            ChatAction.DiscardHandoff,
            ChatAction.CloseHandoffSheet,
        )

        viewModelOwned.forEach { action ->
            assertNull("hostEffect for $action", ChatDispatch.hostEffect(action))
            assertNull("permissionDecision for $action", ChatDispatch.permissionDecision(action))
        }
    }

    private enum class Owner { Content, Dispatch, ContentDispatch, Host, DraftGate, HostOnly }

    private data class LedgerRow(
        val n: Int,
        val element: String,
        val owner: Owner,
        val actions: List<ChatAction> = emptyList(),
    )

    @Test
    fun `the 35-row interaction ledger is complete and every row is owned`() {
        val ref = ProjectRef(
            id = UUID.randomUUID(),
            label = "Project",
            repoDir = File("build/test-tmp/project"),
        )
        val rows = listOf(
            LedgerRow(1, "Top bar leading", Owner.ContentDispatch, listOf(ChatAction.OpenSessions)),
            LedgerRow(2, "New chat", Owner.Dispatch, listOf(ChatAction.NewChat)),
            // Today one tap both opens the sheet and fetches the catalogue;
            // the split contract names both effects, and ReloadModels has no
            // separate row of its own.
            LedgerRow(
                3,
                "Model chip",
                Owner.ContentDispatch,
                listOf(ChatAction.OpenModels, ChatAction.ReloadModels),
            ),
            LedgerRow(4, "Resume Continue", Owner.ContentDispatch, listOf(ChatAction.OpenHandoffInCode)),
            LedgerRow(5, "Resume trust Confirm", Owner.HostOnly),
            LedgerRow(6, "Resume trust Dismiss", Owner.HostOnly),
            LedgerRow(7, "Retry", Owner.Dispatch, listOf(ChatAction.Retry("prior"))),
            LedgerRow(8, "Use this plan in Code", Owner.Dispatch, listOf(ChatAction.UsePlanInCode(2))),
            LedgerRow(9, "File changes panel", Owner.Dispatch, listOf(ChatAction.OpenReview)),
            LedgerRow(10, "Permission dismiss", Owner.Dispatch, listOf(ChatAction.DenyPermission("p1"))),
            LedgerRow(11, "Allow once", Owner.Dispatch, listOf(ChatAction.AllowOnce("p1"))),
            LedgerRow(12, "Always", Owner.Dispatch, listOf(ChatAction.AllowForSession("p1"))),
            LedgerRow(13, "Deny", Owner.Dispatch, listOf(ChatAction.DenyPermission("p1"))),
            LedgerRow(14, "Question dismiss", Owner.Dispatch, listOf(ChatAction.ReleaseQuestion)),
            LedgerRow(15, "Question Deny", Owner.Dispatch, listOf(ChatAction.ReleaseQuestion)),
            LedgerRow(16, "Question Dismiss", Owner.Dispatch, listOf(ChatAction.ReleaseQuestion)),
            LedgerRow(17, "Draft change", Owner.DraftGate, listOf(ChatAction.DraftChanged("x"))),
            LedgerRow(18, "Send", Owner.Dispatch, listOf(ChatAction.Send)),
            LedgerRow(19, "Stop", Owner.Dispatch, listOf(ChatAction.Stop)),
            LedgerRow(20, "IME send", Owner.Dispatch, listOf(ChatAction.Send)),
            LedgerRow(21, "Session pick", Owner.ContentDispatch, listOf(ChatAction.SelectSession("s"))),
            LedgerRow(22, "Session delete", Owner.Dispatch, listOf(ChatAction.DeleteSession("s"))),
            LedgerRow(23, "Sheet New chat", Owner.ContentDispatch, listOf(ChatAction.NewChat)),
            LedgerRow(24, "Sheet Review", Owner.Dispatch, listOf(ChatAction.OpenReview)),
            LedgerRow(25, "Sheet dismiss", Owner.Content),
            LedgerRow(26, "Model pick", Owner.ContentDispatch, listOf(ChatAction.SelectModel("m"))),
            LedgerRow(27, "Model sheet dismiss", Owner.Content),
            LedgerRow(28, "Summary edit", Owner.Content),
            LedgerRow(29, "Destination select", Owner.Content),
            LedgerRow(
                30,
                "Handoff Confirm",
                Owner.ContentDispatch,
                listOf(ChatAction.ConfirmHandoff(destinationId = "dest", summary = "plan")),
            ),
            LedgerRow(31, "Sheet dismiss", Owner.Dispatch, listOf(ChatAction.CloseHandoffSheet)),
            LedgerRow(32, "Discard", Owner.Dispatch, listOf(ChatAction.DiscardHandoff)),
            LedgerRow(33, "Open in Code", Owner.ContentDispatch, listOf(ChatAction.OpenHandoffInCode)),
            LedgerRow(34, "Trust Confirm", Owner.HostOnly, listOf(ChatAction.GrantHandoffTrust(ref))),
            LedgerRow(35, "Trust Dismiss", Owner.HostOnly),
        )

        assertEquals(35, rows.size)
        assertEquals((1..35).toList(), rows.map { it.n })
        assertEquals(
            "interaction ledger elements must stay in document order",
            rows.map { it.element },
            rows.sortedBy { it.n }.map { it.element },
        )

        val ledgerActions = rows.flatMap { it.actions }
        // One representative per ChatAction subtype: all 22 appear. The two
        // former sheet actions (Settings, Projects) are gone: V2-3 moved those
        // transitions to the shell, so they are no longer chat interactions.
        assertEquals(22, ledgerActions.distinct().size)

        rows.filter { it.actions.isEmpty() }.forEach { row ->
            assertTrue(
                "row ${row.n} (${row.element}) has no action but is not content/host-only",
                row.owner == Owner.Content || row.owner == Owner.HostOnly,
            )
        }

        val viewModelOwned = listOf(
            ChatAction.DraftChanged("x"),
            ChatAction.Send,
            ChatAction.Stop,
            ChatAction.NewChat,
            ChatAction.OpenSessions,
            ChatAction.OpenModels,
            ChatAction.SelectModel("m"),
            ChatAction.ReloadModels,
            ChatAction.SelectSession("s"),
            ChatAction.DeleteSession("s"),
            ChatAction.ReleaseQuestion,
            ChatAction.Retry("x"),
            ChatAction.UsePlanInCode(1),
            ChatAction.DiscardHandoff,
            ChatAction.CloseHandoffSheet,
        )

        ledgerActions.forEach { action ->
            val hosted = ChatDispatch.hostEffect(action)
            val permission = ChatDispatch.permissionDecision(action)
            when {
                hosted != null -> assertTrue("$action must not map to None", hosted != ChatHostEffect.None)
                permission != null -> Unit
                else -> assertTrue(
                    "$action is unowned: no host effect, no permission, not a ViewModel command",
                    viewModelOwned.any { it::class == action::class },
                )
            }
        }

        // Appendix C.3 effect table: four host continuations, three permission
        // decisions, fifteen ViewModel commands. ConfirmHandoff is an explicit
        // host continuation because its trust callback must survive the seam.
        val distinct = ledgerActions.distinct()
        assertEquals(4, distinct.count { ChatDispatch.hostEffect(it) != null })
        assertEquals(3, distinct.count { ChatDispatch.permissionDecision(it) != null })
        assertEquals(
            15,
            distinct.count {
                ChatDispatch.hostEffect(it) == null &&
                    ChatDispatch.permissionDecision(it) == null
            },
        )
    }
}
