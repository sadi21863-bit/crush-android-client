package com.crush.mobile.data.handoff

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One persisted Chat to Code handoff (P6, `crush-plan-2026-11-02` §9): a
 * proposed, user-approved context package - the summary the user reviewed
 * and the snippets they explicitly attached - bound to the project it will
 * open a Code session in. Every §9 field is carried VERBATIM as its own
 * column, plus the `status` machine the build brief adds. Column names are
 * §9's camelCase exactly (the projects table's snake_case is that table's
 * local convention, not a registry-wide rule; renaming §9's fields would
 * break the verbatim claim).
 *
 * NULL-until-stage ([targetCodeSessionId], [userApprovedAt],
 * [projectTreeFingerprintAtHandoff]) is the MIGRATION_1_2 nullable-columns
 * discipline carried over: NULL means "this stage has not happened", and
 * only the DAO's guarded transitions move a column out of NULL. A DRAFT row
 * ships all three as null.
 *
 * [selectedReferences] stores TEXT with NO Room TypeConverter (no converter
 * precedent exists in this repo yet): the value is a JSON-encoded array of
 * the attached snippets, or the EMPTY STRING when none - the plan's exact
 * encoding rule. Batch A treats it as opaque transport; Batch B's package
 * model owns the decode.
 *
 * [expiryPolicy] is RESERVED, not enforced: v1 writes [EXPIRY_POLICY_NONE]
 * and nothing reads it (design §6 deferred expiry semantics). The column is
 * NOT NULL so "no policy" can never be confused with a future real value.
 *
 * Deliberately NO @ForeignKey to projects and no cascading deletes: the row
 * is a long-lived trace ("handoff remains traceable and resumable after app
 * restart", proof row (b)). Removing a project
 * (RoomProjectStore.remove - whose statement also deletes that row's
 * consent record) must not destroy the handoff history that pointed at it;
 * a dangling targetProjectId is the visible trace that the target was
 * removed, and the resume path re-gates through the trust service instead
 * of trusting the id.
 *
 * The `status` CHECK lives in the hand-written MIGRATION_2_3 DDL, not in
 * this annotation: Room carries no CHECK support in @Entity, and a
 * @ColumnInfo(defaultValue) would make Room EXPECT a DEFAULT clause the
 * hand-written SQL does not write. Enforcement has two layers - the
 * migrated table's CHECK rejects an out-of-body status at SQLite level,
 * and every HandoffDao transition is a targeted UPDATE guarded by
 * `WHERE status = expected`, which covers every install (a fresh install
 * creates this table from Room's generated DDL, which carries no CHECK).
 */
@Entity(tableName = "handoffs")
data class HandoffEntity(
    /** UUID string, same discipline as ProjectEntity.id - never a label. */
    @PrimaryKey @ColumnInfo(name = "handoffId") val handoffId: String,

    /** The engine chat session id the source conversation ran in. */
    @ColumnInfo(name = "sourceChatId") val sourceChatId: String,

    /** A ProjectEntity.id UUID - the ADR-0001 chain, never a label/path alias. */
    @ColumnInfo(name = "targetProjectId") val targetProjectId: String,

    /** The target Code session id; NULL until the DISPATCHED transition. */
    @ColumnInfo(name = "targetCodeSessionId") val targetCodeSessionId: String? = null,

    /** The summary the user reviewed and edited; NOT the raw transcript. */
    @ColumnInfo(name = "summaryMarkdown") val summaryMarkdown: String,

    /** The user's confirmation stamp; NULL until the APPROVED transition. */
    @ColumnInfo(name = "userApprovedAt") val userApprovedAt: Long? = null,

    /**
     * JSON-encoded array or the empty string (none). See class KDoc.
     */
    @ColumnInfo(name = "selectedReferences") val selectedReferences: String = "",

    /** Computed AT dispatch; NULL until then. Design §4(5). */
    @ColumnInfo(name = "projectTreeFingerprintAtHandoff")
    val projectTreeFingerprintAtHandoff: String? = null,

    /** RESERVED in v1 - always [EXPIRY_POLICY_NONE], never read. */
    @ColumnInfo(name = "expiryPolicy") val expiryPolicy: String = EXPIRY_POLICY_NONE,

    /** Which provider/model served the source chat (its origin is a §12 fact). */
    @ColumnInfo(name = "sourceProvider") val sourceProvider: String,

    /** DRAFT default; other values arrive ONLY via HandoffDao's guarded transitions. */
    @ColumnInfo(name = "status") val status: String = STATUS_DRAFT,
) {
    companion object {
        /**
         * v1's reserved value for [expiryPolicy]: expiry semantics are
         * design §6 work, deliberately not v1; the column is carried so the
         * §9 field list is complete, and enforcement is NOT v1 scope.
         */
        const val EXPIRY_POLICY_NONE = "NONE"

        /**
         * The only status a direct constructor ever needs. The other three
         * ("APPROVED", "DISPATCHED", "ABANDONED") are written exclusively by
         * HandoffDao's guarded SQL, so there is no Kotlin-side constant to
         * drift against the CHECK clause in MIGRATION_2_3 - callers never
         * mint them by hand.
         */
        const val STATUS_DRAFT = "DRAFT"
    }
}
