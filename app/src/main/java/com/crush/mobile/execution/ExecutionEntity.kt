package com.crush.mobile.execution

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * V2-16 A2 slice 1: a durable RUN record, not a transcript.
 *
 * No prompt text, no message body, no provider key and no credential is stored
 * here. [promptRef] is reserved for A3's prompt identity work and slice 1
 * NEVER writes it. The row exists so a process death after submission has a
 * defined, observable outcome instead of a silently lost execution.
 *
 * Deliberately NO @ForeignKey/cascade to projects: like HandoffEntity, the
 * trace must survive project removal. Column names are the design §5 names
 * verbatim; the hand-written MIGRATION_3_4 DDL must match them exactly.
 */
@Entity(
    tableName = "executions",
    indices = [
        Index(value = ["session_id", "updated_at_ms"], name = "idx_exec_session"),
        Index(value = ["engine_key_token", "phase"], name = "idx_exec_engine"),
        Index(value = ["workspace_path"], name = "idx_exec_path"),
    ],
)
data class ExecutionEntity(
    @PrimaryKey @ColumnInfo(name = "execution_id") val executionId: String,
    @ColumnInfo(name = "run_id") val runId: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    /** "" = legacy, "chat" = chat pool, "proj:<uuid>" = project instance. */
    @ColumnInfo(name = "engine_key_token") val engineKeyToken: String,
    /** Byte-exact engine cwd; never a label or a normalized path. */
    @ColumnInfo(name = "workspace_path") val workspacePath: String,
    /** Observed only; NULL means unknown, never a guessed value. */
    @ColumnInfo(name = "engine_generation") val engineGeneration: Long? = null,
    /** Design §5 canonical name. NULL means unknown, never a guessed value. */
    @ColumnInfo(name = "lease_epoch_ms") val leaseEpochMs: Long? = null,
    /**
     * Deprecated v4 name retained as an additive column so the shipped
     * MIGRATION_3_4 is never rewritten in place. New writes use [leaseEpochMs].
     */
    @ColumnInfo(name = "lease_at_ms") val leaseAtMs: Long? = null,
    /** [ExecutionLifecycle] name. */
    @ColumnInfo(name = "phase") val phase: String,
    @ColumnInfo(name = "progress_at_ms") val progressAtMs: Long? = null,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
    /** Reserved for A3; slice 1 leaves it NULL. */
    @ColumnInfo(name = "prompt_ref") val promptRef: String? = null,
    @ColumnInfo(name = "terminal_reason") val terminalReason: String? = null,
    /** [ExecutionAckState] name. */
    @ColumnInfo(name = "ack_state") val ackState: String,
)

/** Local ack states; "not sent yet" is a real state, not an unacknowledged send. */
enum class ExecutionAckState {
    INTENT_ONLY,
    SENT_UNCONFIRMED,
    ACKED,
    AMBIGUOUS,
}
