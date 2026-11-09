package com.crush.mobile.domain.handoff

import com.crush.mobile.data.handoff.HandoffEntity
import com.google.gson.GsonBuilder
import java.util.UUID

/**
 * The P6 handoff package (Batch B, build plan `build-plan-handoff-p6-2026-11-08`
 * §4 b1): exactly what the consent sheet shows and dispatch sends - the
 * user-reviewed summary, the snippets the user explicitly attached, and the
 * provenance bits the first-turn preamble needs. The plan's law (plan :407
 * "must not copy secrets or the full transcript automatically", design
 * §4(2)): content enters ONLY as composer-shown text - never an automatic
 * transcript copy, so nothing beyond [summaryMarkdown],
 * [selectedReferences] and the provenance labels belongs here.
 *
 * Field-to-§9 mapping: the package carries handoffId, sourceChatId,
 * targetProjectId (the ADR-0001 stable Room row id - never a label),
 * summaryMarkdown, selectedReferences, sourceProvider and - as READ-BACK
 * provenance - userApprovedAt. The provenance labels the row does not store
 * (sourceChatTitle/sourceChatTime/createdAt) are in-memory composition
 * facts: the §9 schema has no column for them (HandoffDao's newest-first
 * view documents the same absence), so from(entity) re-materializes them as
 * the documented empty defaults and the resume path re-derives display
 * provenance from the live source chat (build plan g2).
 *
 * [userApprovedAt] is intentionally present and READ-ONLY: from(entity)
 * carries what the row already approved with (the template line the
 * dispatch turn renders), while toEntity leaves it null - status-machine
 * values are minted ONLY by HandoffDao's guarded transitions, never by this
 * mapper. A package-to-entity write is a DRAFT row by construction.
 */
data class HandoffPackage(
    /**
     * Generates a UUID when not supplied (same discipline as
     * ProjectEntity.id); copy() keeps the minted id stable.
     */
    val handoffId: String = UUID.randomUUID().toString(),

    /** The engine chat session id the source conversation ran in. */
    val sourceChatId: String,

    /** A ProjectEntity.id UUID - the ADR-0001 chain, never a label/path alias. */
    val targetProjectId: String,

    /** The summary the user reviewed and edited; NOT the raw transcript. */
    val summaryMarkdown: String,

    /**
     * The code-block snippets the user explicitly attached (the exact text
     * the composer's Copy/Save chips extract). Only explicit user selection
     * populates this; nothing auto-selects. Decoded from the row's
     * JSON-encoded array by [from].
     */
    val selectedReferences: List<String> = emptyList(),

    /** Display provenance: the source session's title as the app knows it. */
    val sourceChatTitle: String,

    /**
     * Display provenance: a caller-ready timestamp string for the source
     * conversation (the ui ChatMessage carries no clock, so the composer
     * supplies what its session list knows). null renders "(not recorded)".
     */
    val sourceChatTime: String? = null,

    /**
     * Which provider/model served the source chat. The row column is NOT
     * NULL (§9), so the package default is "" = not-yet-resolved; the exact
     * value origin is the build plan's §12 owner fact 2, and dispatch passes
     * its resolved value to firstPrompt explicitly.
     */
    val sourceProvider: String = "",

    /**
     * READ-BACK provenance only: what from(entity) carried in from the row
     * HandoffDao.markApproved stamped. Written by the DAO, never by
     * [toEntity].
     */
    val userApprovedAt: Long? = null,

    /**
     * When the package was minted. The §9 row stores no creation clock, so
     * [from] re-materializes 0L (the domain ChatMessage.createdAt=0
     * "absent clock" precedent) - the row's trace ordering is rowid order
     * by design (HandoffDao's newest-first doc).
     */
    val createdAt: Long = System.currentTimeMillis(),
) {

    /**
     * The package's persistence row: a DRAFT-shaped [HandoffEntity] by
     * construction - status, userApprovedAt and targetCodeSessionId arrive
     * ONLY via HandoffDao's guarded transitions, so the mapper writes all
     * three as their untouched defaults. The row never carries provenance
     * display labels (see class KDoc); a draft's approval goes to the row
     * through markApproved, never through a re-minted entity.
     *
     * [enrichmentFingerprint] is named for its role: it enriches the row's
     * `projectTreeFingerprintAtHandoff` column with the dispatch-time tree
     * walk (design §4(5)), a value the PACKAGE deliberately does not carry.
     * null leaves the column at its NULL-until-dispatch default.
     */
    fun toEntity(enrichmentFingerprint: String?): HandoffEntity =
        HandoffEntity(
            handoffId = handoffId,
            sourceChatId = sourceChatId,
            targetProjectId = targetProjectId,
            summaryMarkdown = summaryMarkdown,
            selectedReferences = encodeReferences(selectedReferences),
            projectTreeFingerprintAtHandoff = enrichmentFingerprint,
            sourceProvider = sourceProvider,
        )

    companion object {

        /**
         * Re-materializes the package from a persisted row. Fields the row
         * stores map verbatim; every field §9 has no column for degrades to
         * the documented empty default (class KDoc). [selectedReferences]
         * decode is FAIL-SOFT: a malformed or foreign-encoded cell (the
         * Batch A roundtrip test wrote opaque-transport JSON, e.g. an object
         * array) yields no references rather than throwing - the column's
         * schema shape is "JSON array of snippets or empty string", and a
         * degraded read must never kill a re-open.
         */
        fun from(entity: HandoffEntity): HandoffPackage = HandoffPackage(
            handoffId = entity.handoffId,
            sourceChatId = entity.sourceChatId,
            targetProjectId = entity.targetProjectId,
            summaryMarkdown = entity.summaryMarkdown,
            selectedReferences = decodeReferences(entity.selectedReferences),
            sourceChatTitle = "",
            sourceChatTime = null,
            sourceProvider = entity.sourceProvider,
            userApprovedAt = entity.userApprovedAt,
            createdAt = 0L,
        )

        /**
         * The row's §9 encoding (HandoffEntity's column contract): a
         * JSON-encoded array of the snippet texts, the EMPTY STRING when
         * none. Plain strings, not objects - the composer's code-block Copy
         * extracts a text, and the package model owns this codec (the
         * entity treats the cell as opaque transport by design).
         */
        private fun encodeReferences(references: List<String>): String =
            if (references.isEmpty()) "" else GsonBuilder().disableHtmlEscaping().create().toJson(references)

        private fun decodeReferences(encoded: String): List<String> =
            if (encoded.isBlank()) {
                emptyList()
            } else {
                runCatching {
                    GsonBuilder().disableHtmlEscaping().create()
                        .fromJson(encoded, Array<String>::class.java).toList()
                }.getOrDefault(emptyList())
            }
    }
}
