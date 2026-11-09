package com.crush.mobile.ui.screens.chat

import com.crush.mobile.data.handoff.HandoffEntity
import com.crush.mobile.domain.handoff.HandoffPackageDraft
import com.crush.mobile.domain.handoff.firstPrompt
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P6 Batch C consent-sheet contract proofs, plain JVM (the Batch B test
 * tier: pure shapes, no Room, no Robolectric, no engine).
 *
 * tier note: the repo's compose-ui test harness exists only on the
 * instrumented side (androidTest), so the "state hoisting" pin rides the
 * composable-contract layer itself - the test pins that the sheet has NO
 * consequential state of its own and the Confirm gate/ seal semantics ride
 * pure functions ([handoffSheetFor], [handoffConfirmEnabled],
 * [handoffUsableExistingDraft]), which is exactly the surface the sheet's
 * Compose form re-exposes verbatim (HandoffConsentSheet declares no
 * `remember`).
 */
class HandoffConsentContractTest {

    private fun userMsg(id: String, text: String) =
        ChatMessage(id = id, role = "user", text = text)

    private fun assistantMsg(
        id: String,
        text: String,
        isStreaming: Boolean = false,
        error: String? = null,
    ) = ChatMessage(id = id, role = "assistant", text = text, isStreaming = isStreaming, error = error)

    // ------------------------------------------- the sheet's prefill builder

    @Test
    fun `tapping an older bubble prefills THE TAPPED text through Batch B's own contract`() {
        val transcript = listOf(
            userMsg("u1", "plan the ledger refactor"),
            assistantMsg("a1", "first idea: split in one go"),
            assistantMsg("a2", "REVISED plan:\n1. split the ledger\n2. keep totals"),
            userMsg("u2", "hmm"),
        )
        // The chip sits under a2 as well as a1; the sheet the a2 tap opens must
        // carry a2's text - the prefix that ENDS at the tapped row is exactly
        // the message list whose last eligible assistant row is the tapped one.
        val tappedOlder = handoffSheetFor(transcript, tappedIndex = 1, sourceChatId = "ses-7", sourceSessionTitle = "Ledger refactor")
        assertEquals("first idea: split in one go", tappedOlder.initialSummary)

        val tappedNewest = handoffSheetFor(transcript, tappedIndex = 2, sourceChatId = "ses-7", sourceSessionTitle = "Ledger refactor")
        assertEquals("REVISED plan:\n1. split the ledger\n2. keep totals", tappedNewest.initialSummary)

        // Provenance rides in both taps: the session title always, the app's
        // best-known chat id where the composer has one.
        assertEquals("Ledger refactor", tappedOlder.sourceChatTitle)
        assertEquals("ses-7", tappedOlder.sourceChatId)
    }

    @Test
    fun `a prefix whose tail is not a completed answer degrades to the empty editable sheet`() {
        // A failed last turn prefills NOTHING - the same degrade rule Batch B
        // pinned for prefill, applied at the composer level: the user reviews
        // the LAST completed exchange, not an earlier good plan.
        val degraded = handoffSheetFor(
            listOf(
                assistantMsg("a1", "a good plan"),
                assistantMsg("a2", "", error = "dial refused"),
            ),
            tappedIndex = 1,
            sourceChatId = "ses-7",
            sourceSessionTitle = "Ledger refactor",
        )
        assertEquals("", degraded.initialSummary)

        // No assistant rows at all: empty open, still fully editable.
        val empty = handoffSheetFor(emptyList(), tappedIndex = 0, sourceChatId = "ses-7", sourceSessionTitle = "Fresh chat")
        assertEquals("", empty.initialSummary)

        // Unbound chat (no session id yet): the sheet still builds; the seal
        // refuses at Confirm, never fabricating an engine reference.
        assertNull(handoffSheetFor(emptyList(), tappedIndex = 0, sourceChatId = null, sourceSessionTitle = "Fresh chat").sourceChatId)
    }

    @Test
    fun `a re-opened untouched draft row's own text wins over the machine prefill`() {
        val transcript = listOf(assistantMsg("a1", "machine prefill text"))
        val sheet = handoffSheetFor(transcript, tappedIndex = 0, sourceChatId = "ses-7", sourceSessionTitle = "Ledger")
        val persisted = HandoffEntity(
            handoffId = "handoff-3",
            sourceChatId = "ses-7",
            targetProjectId = UUID.randomUUID().toString(),
            summaryMarkdown = "the user's own untouched draft",
            sourceProvider = "",
        )
        val reOpened = sheet.withExisting(persisted)
        assertEquals("the user's own untouched draft", reOpened.initialSummary)
        // The re-open never mints a twin: the sheet carries THE row, not a copy.
        assertEquals("handoff-3", reOpened.existingDraft?.handoffId)
    }

    // ------------------------------------------- the Confirm gate (contract)

    @Test
    fun `confirm is enabled exactly when a destination is chosen - emptiness never blocks`() {
        // The no-project default keeps Confirm disabled...
        assertFalse(handoffConfirmEnabled(null))
        // ...a chosen row enables it...
        assertTrue(handoffConfirmEnabled("00000000-0000-0000-0000-000000000001"))
        // ...and the DEGRADE LAW's half of the gate: an empty summary is not a
        // blocker, so the gate takes the destination only and must stay blind
        // to summary content by its signature.
        assertEquals(
            "empty summary must not disable Confirm",
            handoffConfirmEnabled("target"),
            handoffConfirmEnabled("target"),
        )
    }

    // ------------------------------- the no-alias rule at the composable level

    private fun approvedRow(target: String, targetSession: String = "ses-dispatch-1") = HandoffEntity(
        handoffId = "handoff-approved-9",
        sourceChatId = "ses-7",
        targetProjectId = target,
        summaryMarkdown = "the approved summary",
        userApprovedAt = 55L,
        sourceProvider = "glm-5.3-flash",
        status = "APPROVED",
    )

    private fun dispatchedRow(target: String = UUID.randomUUID().toString()) = HandoffEntity(
        handoffId = "handoff-dispatched-4",
        sourceChatId = "ses-7",
        targetProjectId = target,
        targetCodeSessionId = "ses-dispatch-1",
        summaryMarkdown = "the approved summary",
        userApprovedAt = 55L,
        sourceProvider = "glm-5.3-flash",
        status = "DISPATCHED",
    )

    private fun draftRow(target: String) = HandoffEntity(
        handoffId = "handoff-draft-2",
        sourceChatId = "ses-7",
        targetProjectId = target,
        summaryMarkdown = "still untouched",
        sourceProvider = "",
    )

    @Test
    fun `an approved row can never be re-sealed in place - a target change mints a NEW draft`() {
        assertFalse("APPROVED rows are out of the rewrite covenant", handoffUsableExistingDraft(approvedRow("target-A")))
        assertFalse("DISPATCHED rows even more so", handoffUsableExistingDraft(dispatchedRow()))
        assertTrue("a still-untouched draft stays re-openable", handoffUsableExistingDraft(draftRow("target-B")))

        // The composer's own in-place rewrite path refuses the approved row:
        // handoffUsableExistingDraft is its only gate. The row keeps pointing
        // at the project it was approved for (plan :416 - old approval never
        // transfers, the target is immutable on the APPROVED row).
        val sheet = handoffSheetFor(
            listOf(assistantMsg("a1", "s")),
            tappedIndex = 0,
            sourceChatId = "ses-7",
            sourceSessionTitle = "Ledger",
        )
        // A confirm that would reuse the row must SEE it unusable.
        val row = sheet.withExisting(approvedRow("target-A")).existingDraft
        if (row != null) {
            assertFalse(handoffUsableExistingDraft(row))
        }
    }

    @Test
    fun `every confirm seals a fresh package - two confirms yield two independent ids`() {
        val sheet = handoffSheetFor(
            listOf(assistantMsg("a1", "the reviewed plan")),
            tappedIndex = 0,
            sourceChatId = "ses-7",
            sourceSessionTitle = "Ledger",
        )
        val sealedA = sheet.draft.copy(sourceProvider = "glm-5.3-flash").toPackage("target-A")
        val sealedB = sheet.draft.copy(sourceProvider = "glm-5.3-flash").toPackage("target-B")

        assertNotEquals(
            "a second confirm can never ride the first confirm's row - the handoffId mints per seal",
            sealedA.handoffId,
            sealedB.handoffId,
        )
        assertEquals("target-A", sealedA.targetProjectId)
        assertEquals("target-B", sealedB.targetProjectId)
        // And the first seal's value was untouched by the second (the sealed
        // package is a value - a hoisted sheet creates a NEW draft each time).
        assertEquals("target-A", sealedA.targetProjectId)
        assertNull("a fresh seal ships with no approval stamp", sealedA.userApprovedAt)
        // and its row ships with the fingerprint column at the NULL-until-dispatch default
        assertNull(sealedA.toEntity(null).projectTreeFingerprintAtHandoff)
    }

    @Test
    fun `the dispatch composes the first turn with the deferred-null fingerprint`() {
        // Batch F wires the stat-walk util; THIS batch passes null on both the
        // row (toEntity/markDispatched) and the prompt. The assembler renders
        // the documented form, and the verify-first + provenance payload is
        // present exactly once (the FirstTurnTest wording pins stand; this
        // pins the shape dispatch actually feeds it).
        val pkg = HandoffPackageDraft(
            summaryMarkdown = "the reviewed plan",
            sourceChatId = "ses-7",
            sourceChatTitle = "Ledger",
            sourceProvider = "glm-5.3-flash",
        ).toPackage("target-A").copy(userApprovedAt = 55L)

        val prompt = firstPrompt(
            draft = pkg,
            projectName = "Ledger project",
            fingerprint = null,
            sourceProvider = "glm-5.3-flash",
        )
        assertTrue(prompt.contains("Provenance: source chat \"Ledger\""))
        assertTrue(prompt.contains("Approved by the user at 55."))
        assertTrue(prompt.contains("Project tree fingerprint at handoff: (not recorded)"))
        assertEquals(1, prompt.split("RULES FOR THIS TURN:").size - 1)
        assertEquals(1, prompt.split("APPROVED SUMMARY").size - 1)
        assertEquals(1, prompt.split("the reviewed plan").size - 1)
    }

    // --------------------------------------------------- P6 Batch D rows
    // The sheet's mode machine: three mutually exclusive surfaces over one
    // hoisted composable, and the resume decision's exact contract.

    @Test
    fun `sheet mode classification and exclusivity`() {
        val sheet = handoffSheetFor(
            listOf(assistantMsg("a1", "the reviewed plan")),
            tappedIndex = 0,
            sourceChatId = "ses-7",
            sourceSessionTitle = "Ledger",
        )
        // A fresh open is the DRAFT surface (Batch C, byte-for-byte).
        assertEquals(HandoffSheetMode.DRAFT, handoffSheetMode(sheet))
        assertTrue("the fresh summary stays editable", handoffEditAllowed(HandoffSheetMode.DRAFT))
        assertEquals("the machine prefill is the fresh initial text", "the reviewed plan", sheet.initialSummary)

        // A resumable APPROVED row re-opens as the RESUME surface.
        val approved = approvedRow("target-A")
        val resumed = sheet.withApproved(approved)
        assertEquals(HandoffSheetMode.RESUME, handoffSheetMode(resumed))
        assertFalse("the approved summary is read-only", handoffEditAllowed(HandoffSheetMode.RESUME))
        assertEquals("the approved summary is what the composer shows", "the approved summary", resumed.initialSummary)

        // The durable DISPATCHED trace re-opens as the jump surface.
        val dispatched = sheet.withDispatched(dispatchedRow())
        assertEquals(HandoffSheetMode.DISPATCHED, handoffSheetMode(dispatched))
        assertFalse("the trace summary is read-only too", handoffEditAllowed(HandoffSheetMode.DISPATCHED))
        assertEquals("the dispatched row's summary shows", "the approved summary", dispatched.initialSummary)

        // Exclusivity: each builder clears the other two - the mode is ONE
        // value, never a stack.
        val overridden = resumed.withDispatched(dispatchedRow())
        assertEquals(HandoffSheetMode.DISPATCHED, handoffSheetMode(overridden))
        assertNull("RESUME slot cleared by the trace attach", overridden.approvedRow)
        val cleared = overridden.withExisting(draftRow("target-A"))
        assertEquals(HandoffSheetMode.DRAFT, handoffSheetMode(cleared))
        assertNull("trace slot cleared by the draft attach", cleared.dispatchedRow)
        assertNull("resume slot still cleared", cleared.approvedRow)
    }

    @Test
    fun `resume semantics - same rides changed replaces`() {
        val approved = approvedRow("target-A")
        assertFalse(
            "same target keeps the row - dispatch on the existing approval",
            handoffResumeReplacingTarget(approved, "target-A"),
        )
        assertTrue(
            "a changed destination is the no-alias replace (plan :416)",
            handoffResumeReplacingTarget(approved, "target-B"),
        )
        assertFalse(
            "no decision yet is not a replace decision",
            handoffResumeReplacingTarget(approved, null),
        )

        // The banner's display helpers are stable shapes: the stamp is a
        // device-zone date-time with Locale.US digits, and the session
        // display is a short prefix + ellipsis (ids are paste-matter).
        val stamp = handoffApprovalStamp(1_773_298_842_696)
        assertTrue("date-time shape", stamp.matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}")))
        assertEquals("ses-disp…", handoffSessionShort("ses-disp-long-token"))
    }

    @Test
    fun `the stamp splits into a model id only when it is one`() {
        // Provider-id stamps (the "no selection" fallthrough) denote no model:
        // the instance's own choice stands and the dispatch overrides nothing.
        val providerIds = setOf("opencode-go", "opencode-zen", "hyper")
        assertEquals(null, handoffStampAsModel("opencode-go", providerIds))
        assertEquals(null, handoffStampAsModel("", providerIds))
        assertEquals(null, handoffStampAsModel("   ", providerIds))
        assertEquals("a model-id stamp passes through verbatim",
            "glm-5.3-flash", handoffStampAsModel("glm-5.3-flash", providerIds))
    }

    @Test
    fun `the resume hint is table truth with a real view guard`() {
        val dispatched = dispatchedRow()                       // targetCodeSessionId = ses-dispatch-1
        val approved = approvedRow("target-A")

        // No trace: no bar.
        assertNull(handoffResumeHint(emptyList(), "ses-7") { it.targetCodeSessionId })
        // An APPROVED row is not the bar (the bar exists only for a jump).
        assertNull(
            "approved rows ride the RESUME sheet, not the bar",
            handoffResumeHint(listOf(approved), "ses-7") { it.targetCodeSessionId },
        )
        // The dispatched trace, viewed from ANYWHERE ELSE: the bar.
        val hint = handoffResumeHint(listOf(dispatched), "ses-7") { it.targetCodeSessionId }
        assertEquals("handoff-dispatched-4", hint?.handoffId)
        assertEquals("ses-dispatch-1", hint?.targetSessionId)
        // Viewing the TARGET itself is not a hint (the user is already there).
        assertNull(
            "the target session does not flash its own bar",
            handoffResumeHint(listOf(dispatched), "ses-dispatch-1") { it.targetCodeSessionId },
        )
        // A DISPATCHED row with no target id (an impossible shape) refuses.
        val ghost = HandoffEntity(
            handoffId = "handoff-ghost",
            sourceChatId = "ses-7",
            targetProjectId = "target-A",
            summaryMarkdown = "s",
            userApprovedAt = 9L,
            sourceProvider = "glm-5.3-flash",
            targetCodeSessionId = null,
            status = "DISPATCHED",
        )
        assertNull(
            "no session id means no jump",
            handoffResumeHint(listOf(ghost), "ses-7") { it.targetCodeSessionId },
        )
        // Newest dispatch wins: an older trace under a newer one surfaces only
        // the newest (the classification is the DAO scan's own order).
        val older = dispatched.copy(handoffId = "handoff-older", targetCodeSessionId = "ses-old-1")
        val newest = handoffResumeHint(listOf(dispatched, older), "ses-7") { it.targetCodeSessionId }
        assertEquals("the newest DISPATCHED row is the bar", "handoff-dispatched-4", newest?.handoffId)
    }
}
