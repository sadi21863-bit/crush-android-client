package com.crush.mobile.data.handoff

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * All reads/writes for the handoffs table (P6 stage a3, modeled on
 * ProjectDao's surface): the initial DRAFT write is an @Upsert by primary
 * key, and EVERY status-machine move is a TARGETED column UPDATE guarded by
 * `WHERE status = expected` - the exact anti-clobber pattern
 * ProjectDao.updateTrust documents (never an upsert for a state transition,
 * or a racing surface's columns are silently rewritten with stale values).
 * A guard miss returns 0 affected rows and leaves the row untouched, so the
 * DRAFT -> APPROVED -> DISPATCHED order is enforced by the SQL WHERE clause,
 * not by caller discipline. Nothing here throws on a state mismatch;
 * callers decide what a 0 means.
 *
 * No delete operation and no foreign keys (see HandoffEntity's KDoc): the
 * rows are long-lived traces and are never auto-deleted.
 */
@Dao
interface HandoffDao {

    /**
     * The initial write, DRAFT-shaped by construction (the entity's default
     * status). Insert-or-replace by primary key, with the same covenant
     * ProjectDao.upsert states for its rows: a draft may be re-written by
     * its OWN id only while it is UNTOUCHED (still DRAFT, still pre-user
     * approval) - once any guarded transition has landed, a target change
     * is expressed as a NEW handoffId per the plan's no-alias rule (plan
     * :416), never by replacing this row through this method.
     */
    @Upsert
    suspend fun insertDraft(draft: HandoffEntity)

    @Query("SELECT * FROM handoffs WHERE handoffId = :handoffId")
    suspend fun getById(handoffId: String): HandoffEntity?

    /**
     * The live newest-first trace view (the handoff list, and Batch G's
     * resume surface). "Newest" is INSERTION order (normal `rowid`; safe
     * here because the primary key is TEXT, so rowid is the plain
     * insert-order row id) - the §9 schema carries no creation timestamp
     * and the field list is verbatim, so no ordering column exists; the
     * UUID handoffId would NOT sort by time.
     */
    @Query("SELECT * FROM handoffs ORDER BY rowid DESC")
    fun observeLatest(): Flow<List<HandoffEntity>>

    /**
     * The CURRENT-draft lookup: the newest still-DRAFT row for a source
     * chat, or null when that chat has no open draft. This is what the
     * consent sheet re-opens in Batch C instead of minting a twin.
     */
    @Query(
        "SELECT * FROM handoffs WHERE sourceChatId = :sourceChatId AND status = 'DRAFT' " +
            "ORDER BY rowid DESC LIMIT 1"
    )
    suspend fun latestDraftForSource(sourceChatId: String): HandoffEntity?

    /**
     * The source chat's whole trace, newest first - the Batch D
     * classification read for the sheet's open (P6 Batch C/D comment block
     * in ChatViewModel): the newest DISPATCHED row runs the jump surface,
     * the newest APPROVED row the resume surface, else the still-untouched
     * DRAFT takes Batch C's re-open, else the sheet opens the fresh prefill.
     * One query, the branching lives Kotlin-side in one place. No LIMIT: the
     * rows are long-lived traces and the sheet only reads them.
     */
    @Query(
        "SELECT * FROM handoffs WHERE sourceChatId = :sourceChatId " +
            "ORDER BY rowid DESC"
    )
    suspend fun rowsForSource(sourceChatId: String): List<HandoffEntity>

    /**
     * Batch G's cold-boot resume read: the NEWEST DISPATCHED trace globally
     * (insertion order, the observeLatest contract). The row is the durable
     * restart proof - one query, no LIMIT beyond 1 because only the latest
     * dispatch is the resume candidate; older traces stay surfaceable
     * through their own source chats.
     */
    @Query(
        "SELECT * FROM handoffs WHERE status = 'DISPATCHED' " +
            "ORDER BY rowid DESC LIMIT 1"
    )
    suspend fun latestDispatched(): HandoffEntity?

    /**
     * DRAFT -> APPROVED. Writes the user's confirmation stamp - the caller
     * supplies it, so an APPROVED row can be minted only WITH a non-null
     * userApprovedAt (the a5 rule). Returns 0 (no-op, row untouched) when
     * the row is not in DRAFT.
     */
    @Query(
        "UPDATE handoffs SET userApprovedAt = :approvedAtMs, status = 'APPROVED' " +
            "WHERE handoffId = :handoffId AND status = 'DRAFT'"
    )
    suspend fun markApproved(handoffId: String, approvedAtMs: Long): Int

    /**
     * APPROVED -> DISPATCHED. [targetCodeSessionId] and
     * [projectTreeFingerprintAtHandoff] leave NULL here (§9: NULL until
     * dispatch; the fingerprint is computed AT dispatch, design §4(5), and
     * the verify-first turn re-walks and compares it). A 0 return means the
     * row was not APPROVED - and a failed dispatch leaves the row APPROVED
     * and resumable, never a half-claimed DISPATCHED (plan e1).
     *
     * The fingerprint param is NULLABLE (2026-11-09, Batch C gate fix):
     * Batch C defers the stat-walk to Batch F, and a deferred dispatch must
     * keep storing NULL rather than an empty string - the column's "not
     * recorded yet" state and a real digest are different facts. The
     * fingerprint is a required ingredient of the first turn by CONTRACT,
     * deferred as a VALUE only: Batch F must supply it at dispatch or the
     * row keeps its NULL (the assembler renders the documented
     * "(not recorded)" degradation form).
     */
    @Query(
        "UPDATE handoffs SET targetCodeSessionId = :targetCodeSessionId, " +
            "projectTreeFingerprintAtHandoff = :projectTreeFingerprintAtHandoff, " +
            "status = 'DISPATCHED' " +
            "WHERE handoffId = :handoffId AND status = 'APPROVED'"
    )
    suspend fun markDispatched(
        handoffId: String,
        targetCodeSessionId: String,
        projectTreeFingerprintAtHandoff: String?,
    ): Int

    /**
     * The terminal traced state, allowed FROM DRAFT and APPROVED only: the
     * sheet's cancel path can abandon an untouched draft, and a target
     * change after approval abandons the superseded row (the no-alias rule
     * plan :416 - the replacement is always a NEW handoffId). A DISPATCHED
     * row can NEVER be abandoned: it is the durable cross-restart trace the
     * resume route reads exactly in this state ("the row stays DISPATCHED
     * and surfaceable, never auto-deleted").
     */
    @Query(
        "UPDATE handoffs SET status = 'ABANDONED' " +
            "WHERE handoffId = :handoffId AND status IN ('DRAFT', 'APPROVED')"
    )
    suspend fun abandon(handoffId: String): Int
}
