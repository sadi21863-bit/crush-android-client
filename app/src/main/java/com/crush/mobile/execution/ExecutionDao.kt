package com.crush.mobile.execution

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Guarded transitions only. Every state move is a targeted UPDATE with an
 * expected-phase predicate and an Int affected-row count, in the HandoffDao
 * style: never an @Upsert, so a stale writer cannot clobber a newer phase.
 *
 * Slice 1 writes no prompt reference and no transient text.
 */
@Dao
interface ExecutionDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: ExecutionEntity)

    @Query("SELECT * FROM executions WHERE execution_id = :id")
    suspend fun byId(id: String): ExecutionEntity?

    /**
     * Rows that may still be live after a process death. Terminal phases are
     * excluded explicitly rather than by a negation over an enum column, so
     * an unknown future phase fails closed as "not active".
     */
    @Query(
        "SELECT * FROM executions WHERE phase IN " +
            "('INTENT_RECORDED','SEND_ATTEMPTED','SUBMISSION_ACKNOWLEDGED'," +
            "'RUNNING','CANCELLING') ORDER BY created_at_ms DESC"
    )
    suspend fun recoverableRows(): List<ExecutionEntity>

    /** INTENT_RECORDED -> SEND_ATTEMPTED, paired with the send transition. */
    @Query(
        "UPDATE executions SET phase = 'SEND_ATTEMPTED', " +
            "ack_state = 'SENT_UNCONFIRMED', updated_at_ms = :at " +
            "WHERE execution_id = :id AND phase = 'INTENT_RECORDED'"
    )
    suspend fun markSent(id: String, at: Long): Int

    /** SEND_ATTEMPTED -> SUBMISSION_ACKNOWLEDGED, only before any ambiguity. */
    @Query(
        "UPDATE executions SET phase = 'SUBMISSION_ACKNOWLEDGED', " +
            "ack_state = 'ACKED', updated_at_ms = :at " +
            "WHERE execution_id = :id AND phase = 'SEND_ATTEMPTED' " +
            "AND ack_state = 'SENT_UNCONFIRMED'"
    )
    suspend fun markAcked(id: String, at: Long): Int

    /** A lost acknowledgement: terminal for automation, never auto-re-sent. */
    @Query(
        "UPDATE executions SET phase = 'SEND_ATTEMPTED', " +
            "ack_state = 'AMBIGUOUS', updated_at_ms = :at " +
            "WHERE execution_id = :id AND phase = 'SEND_ATTEMPTED' " +
            "AND ack_state = 'SENT_UNCONFIRMED'"
    )
    suspend fun markAmbiguous(id: String, at: Long): Int

    /** Definitive server rejection (non-retryable 4xx). */
    @Query(
        "UPDATE executions SET phase = 'FAILED', terminal_reason = :reason, " +
            "updated_at_ms = :at WHERE execution_id = :id AND phase = 'SEND_ATTEMPTED'"
    )
    suspend fun markRejected(id: String, reason: String, at: Long): Int

    /** Only a confirmed event may advance a stored phase to RUNNING. */
    @Query(
        "UPDATE executions SET phase = 'RUNNING', updated_at_ms = :at " +
            "WHERE execution_id = :id AND phase = 'SUBMISSION_ACKNOWLEDGED'"
    )
    suspend fun markRunning(id: String, at: Long): Int

    /**
     * The wire carries a run id, not an execution id. The guard also requires
     * a recorded ACKED state, so a confirmed frame can never claim a phase
     * the app has not durably acknowledged.
     */
    @Query(
        "UPDATE executions SET phase = 'RUNNING', updated_at_ms = :at " +
            "WHERE run_id = :runId AND phase = 'SUBMISSION_ACKNOWLEDGED' " +
            "AND ack_state = 'ACKED'"
    )
    suspend fun markRunningByRunId(runId: String, at: Long): Int

    /** General guarded terminal move for a still-live row. */
    @Query(
        "UPDATE executions SET phase = :phase, terminal_reason = :reason, " +
            "updated_at_ms = :at WHERE execution_id = :id AND phase IN " +
            "('INTENT_RECORDED','SEND_ATTEMPTED','SUBMISSION_ACKNOWLEDGED'," +
            "'RUNNING','CANCELLING')"
    )
    suspend fun markTerminal(id: String, phase: String, reason: String?, at: Long): Int

    /** Recovery outcome for a persisted intent whose send never happened. */
    @Query(
        "UPDATE executions SET phase = 'UNSENT', terminal_reason = :reason, " +
            "updated_at_ms = :at WHERE execution_id = :id " +
            "AND phase IN ('INTENT_RECORDED','SEND_ATTEMPTED') " +
            "AND ack_state != 'ACKED'"
    )
    suspend fun markUnsent(id: String, reason: String, at: Long): Int

    /** C2/C3 fail-closed outcome when no atomic attach is possible. */
    @Query(
        "UPDATE executions SET phase = 'LOST', terminal_reason = :reason, " +
            "updated_at_ms = :at WHERE execution_id = :id AND phase IN " +
            "('INTENT_RECORDED','SEND_ATTEMPTED','SUBMISSION_ACKNOWLEDGED'," +
            "'RUNNING','CANCELLING','UNKNOWN')"
    )
    suspend fun markLost(id: String, reason: String, at: Long): Int

    @Query(
        "UPDATE executions SET progress_at_ms = :at, updated_at_ms = :at " +
            "WHERE execution_id = :id AND phase IN ('RUNNING','SUBMISSION_ACKNOWLEDGED')"
    )
    suspend fun observeProgress(id: String, at: Long): Int
}
