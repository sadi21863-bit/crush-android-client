package com.crush.mobile.domain.handoff

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P6 Batch B first-prompt proofs (build plan §4 b4 keeps "template assembly
 * exactness" in Batch E; Batch B pins PRESENCE-AND-ONCE): the assembled turn
 * carries the provenance preamble, the approved summary verbatim, the fenced
 * selected references and the verify-first rules - each present exactly once -
 * and tolerates a null dispatch fingerprint. The template WORDING itself is
 * copied byte-for-byte from `build-plan-handoff-p6-2026-11-08` §Batch E
 * (e3, doc lines 355-376), binding per owner decision 4; Batch E's
 * wording-exactness test pins the full text.
 */
class FirstTurnTest {

    /** Occurrences of [needle] in this string (no stdlib count-of happens here). */
    private fun String.countOf(needle: String): Int = split(needle).size - 1

    private fun aPackage(
        summary: String = "Ship the ledger split, then update the fixtures.",
        references: List<String> = listOf(
            "fun foldTotals(): Int",
            "val existing: Wallet = wallets.first()",
        ),
        sourceChatTime: String? = "2026-11-08 14:22",
        userApprovedAt: Long? = 1_762_000_000_000L,
    ) = HandoffPackage(
        handoffId = "handoff-9",
        sourceChatId = "chat-9",
        targetProjectId = UUID.randomUUID().toString(),
        summaryMarkdown = summary,
        selectedReferences = references,
        sourceChatTitle = "Ledger refactor",
        sourceChatTime = sourceChatTime,
        userApprovedAt = userApprovedAt,
    )

    @Test
    fun `the first turn carries provenance, summary, references and the verify-first rules exactly once`() {
        val prompt = firstPrompt(
            aPackage(),
            projectName = "the Ledger project row",
            fingerprint = "fp-hex-abc",
            sourceProvider = "glm-5.3-flash",
        )

        // Provenance preamble: the provenance line renders exactly as the
        // template wrote it (its hard wrap after "provider" included), the
        // handoffId in the header bracket, and the resolved provider name
        // arrives through the parameter exactly once.
        assertTrue(prompt.startsWith("[HANDOFF handoff-9] This task was handed off"))
        assertEquals(
            1,
            prompt.countOf("Provenance: source chat \"Ledger refactor\" (2026-11-08 14:22), provider"),
        )
        assertEquals(1, prompt.countOf("glm-5.3-flash"))
        assertTrue(prompt.contains("Approved by the user at 1762000000000."))

        // The APPROVED SUMMARY section: the summary rides verbatim, once.
        assertEquals(1, prompt.countOf("APPROVED SUMMARY"))
        assertEquals(1, prompt.countOf("Ship the ledger split, then update the fixtures."))

        // SELECTED REFERENCES: one section header, each snippet in one fence
        // (two blocks, four fence markers).
        assertEquals(1, prompt.countOf("SELECTED REFERENCES:"))
        assertEquals(2, prompt.countOf("```\nfun foldTotals(): Int\n```") +
            prompt.countOf("```\nval existing: Wallet = wallets.first()\n```"))
        assertEquals(4, prompt.countOf("```"))

        // The verify-first instruction: present exactly once, with the plan
        // §9 verbs (verify against reality / list mismatches / no writes
        // until confirmed).
        assertEquals(1, prompt.countOf("RULES FOR THIS TURN:"))
        assertTrue(prompt.contains("1. Inspect the repository against the approved summary."))
        assertTrue(prompt.contains("2. List every mismatch between the summary and current repository reality."))
        assertTrue(prompt.contains("3. Do not write, create, delete, or modify any file in this turn."))
        assertTrue(prompt.contains("4. Await our explicit confirmation after the mismatch list;"))

        // The frozen template has no project-name placeholder: the target
        // project is named by the consent surface (plan :414), never
        // invented into the prompt here.
        assertFalse(prompt.contains("the Ledger project row"))
    }

    @Test
    fun `a null fingerprint and missing provenance render the documented forms`() {
        val prompt = firstPrompt(
            aPackage(
                summary = "s",
                references = emptyList(),
                sourceChatTime = null,
                userApprovedAt = null,
            ),
            projectName = "Ledger",
            fingerprint = null,
            sourceProvider = "glm-5.3-flash",
        )

        // "fingerprint null tolerated": the assembler renders the documented
        // placeholder rather than throwing - a half-assembled row can never
        // be DISPATCHED (the DAO guards it), but the pure function holds.
        assertTrue(
            prompt.contains("Project tree fingerprint at handoff: (not recorded)"),
        )
        assertTrue(prompt.contains("source chat \"Ledger refactor\" (not recorded), provider"))
        assertTrue(prompt.contains("Approved by the user at (not recorded)."))
    }

    @Test
    fun `no selected references renders the none wording`() {
        val prompt = firstPrompt(
            aPackage(references = emptyList()),
            projectName = "Ledger",
            fingerprint = "fp",
            sourceProvider = "glm-5.3-flash",
        )
        assertTrue(prompt.contains("SELECTED REFERENCES:\n(none)"))
        assertEquals(0, prompt.countOf("```"))
    }

    @Test
    fun `placeholder-shaped user content passes through verbatim`() {
        // User-carried text that LOOKS like a template placeholder must ride
        // through byte-for-byte: every non-summary placeholder is
        // substituted over the TEMPLATE only, BEFORE the summary is spliced
        // positionally - nothing is re-scanned after content lands.
        val prompt = firstPrompt(
            aPackage(
                summary = "watch for <summaryMarkdown> and <selectedReferences or \"(none)\"> docs",
                references = listOf(
                    "note <handoffId> in the snippet",
                    "and <userApprovedAt>",
                ),
            ),
            projectName = "Ledger",
            fingerprint = "fp",
            sourceProvider = "glm-5.3-flash",
        )
        assertEquals(
            1,
            prompt.countOf("watch for <summaryMarkdown> and <selectedReferences or \"(none)\"> docs"),
        )
        assertEquals(1, prompt.countOf("```\nnote <handoffId> in the snippet\n```"))
        assertEquals(1, prompt.countOf("```\nand <userApprovedAt>\n```"))
    }
}
