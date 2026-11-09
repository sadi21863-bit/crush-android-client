package com.crush.mobile.domain.handoff

import com.crush.mobile.ui.screens.chat.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P6 Batch B draft proofs (build plan §4 b4, design §5 unit rows verbatim):
 * prefill selection (finished / error-finish / empty-text cases - the plan's
 * degrade rule) and the redaction gate (nothing secret-shaped enters;
 * nothing not shown in the composer enters). Plain JVM, the
 * ChatDenialNoteTest style: pure rows, no Room, no Robolectric.
 */
class HandoffPackageDraftTest {

    private fun userMsg(id: String, text: String) =
        ChatMessage(id = id, role = "user", text = text)

    private fun assistantMsg(
        id: String,
        text: String,
        isStreaming: Boolean = false,
        error: String? = null,
    ) = ChatMessage(
        id = id,
        role = "assistant",
        text = text,
        isStreaming = isStreaming,
        error = error,
    )

    // ------------------------------------------------------------- prefill

    @Test
    fun `prefill takes the last completed assistant message's text as the summary`() {
        val draft = HandoffPackageDraft.prefill(
            listOf(
                userMsg("u1", "plan the ledger refactor"),
                assistantMsg("a1", "draft thinking out loud"),
                assistantMsg("a2", "Here is the plan:\n1. split the ledger"),
                userMsg("u2", "do it"),
            ),
            sourceSessionTitle = "Ledger refactor",
        )

        // The LAST completed assistant row is the summary - its text is what
        // the bubble's Copy action would already hand the user.
        assertEquals("Here is the plan:\n1. split the ledger", draft.summaryMarkdown)
        assertEquals("Ledger refactor", draft.sourceChatTitle)
    }

    @Test
    fun `errors, streaming and empty last assistant turns degrade to the empty editable draft`() {
        // The failed last turn (plan b2: "a failed last turn prefills
        // NOTHING") - and it must NOT fall back to the earlier good turn.
        val failed = HandoffPackageDraft.prefill(
            listOf(assistantMsg("a1", "a good plan"), assistantMsg("a2", "", error = "dial refused")),
            sourceSessionTitle = "Ledger refactor",
        )
        assertEquals("", failed.summaryMarkdown)

        // A mid-stream row is incomplete - the composer never takes partial
        // text, whatever an earlier row said.
        val streaming = HandoffPackageDraft.prefill(
            listOf(assistantMsg("a1", "older"), assistantMsg("a2", "half of a", isStreaming = true)),
            sourceSessionTitle = "Ledger refactor",
        )
        assertEquals("", streaming.summaryMarkdown)

        // No assistant rows at all: the composer opens empty and editable -
        // the flow never depends on a live engine (design §4(1)).
        val none = HandoffPackageDraft.prefill(
            listOf(userMsg("u1", "hello")),
            sourceSessionTitle = "Fresh chat",
        )
        assertEquals("", none.summaryMarkdown)
        val absent = HandoffPackageDraft.prefill(emptyList(), sourceSessionTitle = "Fresh chat")
        assertEquals("", absent.summaryMarkdown)
    }

    @Test
    fun `non-assistant rows are ignored as candidates`() {
        // A user follow-up (or a system note) after a completed answer does
        // not count against the summary: the last ASSISTANT row is the
        // source, whichever rows trail it.
        val draft = HandoffPackageDraft.prefill(
            listOf(
                userMsg("u1", "go"),
                assistantMsg("a1", "The plan, reviewed."),
                userMsg("u2", "and now?"),
            ),
            sourceSessionTitle = "Ledger refactor",
        )
        assertEquals("The plan, reviewed.", draft.summaryMarkdown)
    }

    @Test
    fun `prefill carries the source chat provenance in both shapes of the open`() {
        val healthy = HandoffPackageDraft.prefill(
            listOf(assistantMsg("a1", "the summary")),
            sourceSessionTitle = "Ledger refactor",
        )
        assertEquals("Ledger refactor", healthy.sourceChatTitle)

        // Degraded open: provenance rides the app's session list, not the
        // engine, so the title still lands on the empty-editable draft.
        val degraded = HandoffPackageDraft.prefill(emptyList(), sourceSessionTitle = "Ledger refactor")
        assertEquals("Ledger refactor", degraded.sourceChatTitle)
        assertEquals("", degraded.summaryMarkdown)

        // Only the composer (Batch C) supplies the chat id; prefill never
        // invents one.
        assertEquals(null, degraded.sourceChatId)
    }

    // ---------------------------------------------------------- redaction

    private fun blocked(patternId: String, text: String, extra: List<String> = emptyList()) {
        val verdict = HandoffPackageDraft(summaryMarkdown = text, selectedReferences = extra)
            .verifyContent()
        assertTrue("expected a block for $patternId, got $verdict", verdict is RedactionVerdict.Blocked)
        assertEquals(patternId, (verdict as RedactionVerdict.Blocked).patternId)
    }

    @Test
    fun `a pasted private key block is blocked`() {
        blocked(
            "pem-private-key",
            "the snippet:\n-----BEGIN RSA PRIVATE KEY-----\nMIIEpA...",
        )
        blocked("pem-private-key", "-----BEGIN OPENSSH PRIVATE KEY-----")
    }

    @Test
    fun `a bearer credential run is blocked`() {
        blocked("bearer-token", "Authorization: Bearer 9f8e7d6c5b4a3210fedcba98")
        // Case-insensitive, and an assignment to a token variable rides the
        // same net.
        blocked("bearer-token", "bearer    eyJhbGciOiJIUzI1NiJ9.QXVk.ptgE")
    }

    @Test
    fun `vendor api key literals are blocked`() {
        blocked("api-key-literal", "key = sk-proj-abcdefghijklmnop")
        blocked("api-key-literal", "ghp_123456789012345678901234")
        blocked("api-key-literal", "AKIAIOSFODNN7EXAMPLE")
        blocked("api-key-literal", "xoxb-123456789-abcdef")
    }

    @Test
    fun `a standalone jwt is blocked`() {
        blocked(
            "jwt-token",
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIn0" +
                ".sflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c",
        )
    }

    @Test
    fun `long hex runs are caught by the catch-all`() {
        blocked("long-hex-secret", "secret 0f1e2d3c4b5a6978877a695a4b3c2d1e")
        blocked(
            "long-hex-secret",
            "bazinga 3f2a9c8d7e6f5a4b3c2d1e0f9a8b7c6d5e4f3a2b (40 hex)",
        )
    }

    @Test
    fun `a plain code walk-through summary is not flagged`() {
        val verdict = HandoffPackageDraft(
            summaryMarkdown = """
                ## Goal

                Ship the ledger split. `LedgerViewModel.reduce()` currently folds
                entries in `walletTotals()`, but `WalletRepository.observe()`
                still emits the pre-split stream.

                ## Steps

                1. Split `reduce()` into `splitEntry()` / `foldTotals()`.
                2. Point the repository at the merged stream.
                3. Update `LedgerViewModelTest` fixtures.

                Open question: do migrated wallets keep their totals row, or is
                it rebuilt on first observe?
            """.trimIndent(),
        ).verifyContent()
        assertEquals(RedactionVerdict.Green, verdict)
    }

    @Test
    fun `references are gated with the same net`() {
        // A secret-shaped snippet enters through the references channel: the
        // gate covers it exactly as it covers the summary.
        blocked(
            "api-key-literal",
            "a perfectly innocent summary",
            extra = listOf("val apiKey = sk-live-abcdefghij123456"),
        )

        // And a clean reference on a clean summary stays green.
        val verdict = HandoffPackageDraft(
            summaryMarkdown = "the summary",
            selectedReferences = listOf("fun foldTotals(): Int = 0"),
        ).verifyContent()
        assertEquals(RedactionVerdict.Green, verdict)
    }

    @Test
    fun `blocked verdicts name the pattern and never echo the secret`() {
        val secret = "Bearer 9f8e7d6c5b4a3210fedcba98"
        val verdict = HandoffPackageDraft(summaryMarkdown = "paste this: $secret").verifyContent()
        val blocked = verdict as RedactionVerdict.Blocked
        assertEquals("bearer-token", blocked.patternId)

        // The reason must not carry the offending run back into another
        // surface's state.
        assertTrue(!blocked.reason.contains(secret))
        assertTrue("reason explains the shape", blocked.reason.contains("credential"))
    }

    @Test
    fun `an empty draft is green`() {
        assertEquals(
            RedactionVerdict.Green,
            HandoffPackageDraft(summaryMarkdown = "", selectedReferences = emptyList())
                .verifyContent(),
        )
    }

    @Test
    fun `sealing requires the source chat id and carries the draft into the package`() {
        // Without the app's best-known chat id the draft cannot seal: the
        // row's sourceChatId is NOT NULL and the DAO keys the lookup on it,
        // so refusing here beats writing an anonymous row.
        val sealed = try {
            HandoffPackageDraft(summaryMarkdown = "s").toPackage("target")
            null
        } catch (e: IllegalArgumentException) {
            e
        }
        assertTrue("a seal without the app's known source chat id must be refused", sealed != null)

        val pkg = HandoffPackageDraft(
            summaryMarkdown = "the reviewed summary",
            selectedReferences = listOf("fun a() = 1"),
            sourceChatId = "chat-9",
            sourceChatTitle = "Ledger refactor",
            sourceChatTime = "2026-11-08 14:22",
            sourceProvider = "glm-5.3-flash",
        ).toPackage("proj-uuid")
        assertEquals("chat-9", pkg.sourceChatId)
        assertEquals("proj-uuid", pkg.targetProjectId)
        assertEquals("the reviewed summary", pkg.summaryMarkdown)
        assertEquals(listOf("fun a() = 1"), pkg.selectedReferences)
        assertEquals("Ledger refactor", pkg.sourceChatTitle)
        assertEquals("2026-11-08 14:22", pkg.sourceChatTime)
        assertEquals("glm-5.3-flash", pkg.sourceProvider)

        // A draft that never learned the provider seals with the column's
        // not-yet-resolved stamp (the build plan's §12 owner fact 2).
        assertEquals("", HandoffPackageDraft(summaryMarkdown = "s", sourceChatId = "c")
            .toPackage("p").sourceProvider)
    }
}
