package com.crush.mobile.domain.handoff

import com.crush.mobile.data.handoff.HandoffEntity
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P6 Batch B mapper proofs (build plan §4 b1 + b4): the package owns the
 * entity's `selectedReferences` decode (HandoffEntity's KDoc defers it
 * here), toEntity minting stays DRAFT-shaped (status transitions live ONLY
 * in HandoffDao's guarded SQL), null-fingerprint tolerance, and the honest
 * degradations the §9 row cannot carry (the provenance display labels).
 * Plain JVM - no Room here, the rows are the data class itself.
 */
class HandoffPackageMappingTest {

    /** A fully-known package as Batch C would seal it at approval. */
    private fun aPackage() = HandoffPackage(
        handoffId = "handoff-1",
        sourceChatId = "chat-1",
        targetProjectId = UUID.randomUUID().toString(),
        summaryMarkdown = "Ship the ledger fix",
        selectedReferences = listOf(
            "fun foldTotals(): Int",
            "val existing = wallets.observe()",
        ),
        sourceChatTitle = "Ledger refactor",
        sourceChatTime = "2026-11-08 14:22",
        sourceProvider = "glm-5.3-flash",
        userApprovedAt = 5_000L,
    )

    @Test
    fun `package and entity roundtrip keeps the mappable fields and tolerates a null fingerprint`() {
        val pkg = aPackage()
        val entity = pkg.toEntity(enrichmentFingerprint = null)

        // The mapper never performs a stage transition: a minted row is a
        // DRAFT row with the untouched null-until-stage columns, and the
        // dispatch fingerprint ALONE arrives through the parameter.
        assertEquals(HandoffEntity.STATUS_DRAFT, entity.status)
        assertNull("the mapper never stamps approval", entity.userApprovedAt)
        assertNull(entity.targetCodeSessionId)
        assertNull(entity.projectTreeFingerprintAtHandoff)
        assertEquals("NONE", entity.expiryPolicy)

        // from(entity) re-materializes everything the row stores.
        val rebuilt = HandoffPackage.from(entity)
        assertEquals(pkg.handoffId, rebuilt.handoffId)
        assertEquals(pkg.sourceChatId, rebuilt.sourceChatId)
        assertEquals(pkg.targetProjectId, rebuilt.targetProjectId)
        assertEquals(pkg.summaryMarkdown, rebuilt.summaryMarkdown)
        assertEquals(pkg.selectedReferences, rebuilt.selectedReferences)
        assertEquals(pkg.sourceProvider, rebuilt.sourceProvider)
        assertNull("the row stores no approval for a draft", rebuilt.userApprovedAt)
    }

    @Test
    fun `the fingerprint enriches the row and never rides as package content`() {
        val entity = aPackage().toEntity(enrichmentFingerprint = "fp-hex")
        assertEquals("fp-hex", entity.projectTreeFingerprintAtHandoff)

        // By design the PACKAGE does not carry it: the fingerprint is
        // dispatch-time enrichment (design §4(5)), owned by the row and the
        // verify-first turn's re-walk, never package content - the summary
        // and other row fields re-materialize while the column stays put.
        val rebuilt = HandoffPackage.from(entity)
        assertEquals("fp-hex", entity.projectTreeFingerprintAtHandoff)
        assertEquals(entity.summaryMarkdown, rebuilt.summaryMarkdown)
    }

    @Test
    fun `selectedReferences encodes as a plain json array and the empty string when none`() {
        val encoded = aPackage().toEntity(enrichmentFingerprint = null).selectedReferences
        assertEquals(
            "[\"fun foldTotals(): Int\",\"val existing = wallets.observe()\"]",
            encoded,
        )

        val plain = HandoffPackage(
            sourceChatId = "c",
            targetProjectId = "p",
            summaryMarkdown = "s",
            sourceChatTitle = "",
        )
        assertEquals("", plain.toEntity(enrichmentFingerprint = null).selectedReferences)
        assertEquals(emptyList<String>(), HandoffPackage.from(
            HandoffEntity(
                handoffId = "h", sourceChatId = "c", targetProjectId = "p",
                summaryMarkdown = "s", selectedReferences = "", sourceProvider = "prov",
            ),
        ).selectedReferences)
    }

    @Test
    fun `a foreign selectedReferences blob decodes soft to no references`() {
        // The entity column is opaque transport (Batch A's roundtrip wrote an
        // OBJECT array through it); a shape this codec does not speak must
        // degrade to "no references", never throw a reopen into a crash.
        val rebuilt = HandoffPackage.from(
            HandoffEntity(
                handoffId = "h", sourceChatId = "c", targetProjectId = "p",
                summaryMarkdown = "s",
                selectedReferences = "[{\"label\":\"snippet 1\",\"text\":\"fun a() = 1\"}]",
                sourceProvider = "prov",
            ),
        )
        assertEquals(emptyList<String>(), rebuilt.selectedReferences)
    }

    @Test
    fun `from entity carries the dao stamp and the mapper never writes it back`() {
        // The APPROVED shape exists in the toolchain only through the DAO; a
        // plain JVM mapper test has no DAO to run, so the approved row is
        // constructed here as data - the mapper-read shape, never a mint
        // the production code lets Kotlin write.
        val approved = HandoffEntity(
            handoffId = "h", sourceChatId = "c", targetProjectId = "p",
            summaryMarkdown = "s", userApprovedAt = 1_111L,
            sourceProvider = "prov", status = "APPROVED",
        )
        val pkg = HandoffPackage.from(approved)
        assertEquals("the stamp rides as READ-BACK provenance", 1_111L, pkg.userApprovedAt)

        // ...and the mapper can never hand it back: a package-to-entity
        // write is a DRAFT row by construction (markApproved's guarded SQL
        // is the only writer), the exact "status transitions live in the
        // DAO" rule Batch B must not blur.
        val reminted = pkg.toEntity(enrichmentFingerprint = null)
        assertEquals(HandoffEntity.STATUS_DRAFT, reminted.status)
        assertNull(reminted.userApprovedAt)
    }

    @Test
    fun `from entity defaults the provenance the row does not store`() {
        val rebuilt = HandoffPackage.from(
            HandoffEntity(
                handoffId = "h", sourceChatId = "c", targetProjectId = "p",
                summaryMarkdown = "s", sourceProvider = "prov",
            ),
        )
        // The §9 schema has no column for the display provenance: the row's
        // trace is rowid-ordered by design, so from(entity) hands back the
        // documented empty defaults and the resume path re-derives them
        // from the live source chat.
        assertEquals("", rebuilt.sourceChatTitle)
        assertNull(rebuilt.sourceChatTime)
        assertEquals(0L, rebuilt.createdAt)
        assertEquals("prov", rebuilt.sourceProvider)
    }
}
