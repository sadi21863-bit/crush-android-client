package com.crush.mobile.execution

/**
 * A1 execution-layer value types.
 *
 * Deliberately pure JVM: no Android, no Room, no transport. Persistence and
 * wire plumbing arrive in A2/A3; A1 owns only the in-process execution state,
 * the four independent state dimensions, and the provisional event path.
 */

enum class ExecutionLifecycle {
    INTENT_RECORDED,
    SEND_ATTEMPTED,
    /** Design §9 `send_ambiguous`: terminal for automation, never auto-re-sent. */
    SEND_AMBIGUOUS,
    SUBMISSION_ACKNOWLEDGED,
    RUNNING,
    CANCELLING,
    UNSENT,
    COMPLETED,
    FAILED,
    LOST,
    UNKNOWN,
}

enum class InteractionState { NONE, PERMISSION_PENDING, QUESTION_PENDING, AMBIGUOUS_PROMPT }

enum class ObservationState { DETACHED, OBSERVING, NO_OBSERVABLE_PROGRESS }

enum class CancellationState { NOT_REQUESTED, REQUESTED, REQUEST_RESOLVED, OUTCOME_UNKNOWN }

/** Attribution of a frame to an execution. Never upgrades to ownership by itself. */
enum class Attribution { CONFIRMED, PLAUSIBLE, UNATTRIBUTED }

/** Reconciliation confidence. Only CONFIRMED may produce a definitive claim. */
enum class ReconciliationResult {
    CONFIRMED,
    PLAUSIBLE_BUT_UNPROVEN,
    NO_EVIDENCE,
    INCOMPLETE,
    UNAVAILABLE,
    CONFLICTING,
}

data class ExecutionTarget(
    val instanceKey: String,
    val workspaceId: String,
    val sessionId: String,
)

/** An event observed by the process-scoped adapter. */
data class ExecutionEvent(
    val type: Type,
    val attribution: Attribution,
    val runId: String? = null,
    val sessionId: String? = null,
    val toolCallId: String? = null,
    val detail: String? = null,
    /** Engine generation captured at declare time; NULL means unknown/unproven. */
    val generation: Long? = null,
    /** Explicit execution identity from the declaration; never inferred. */
    val executionId: String? = null,
    /** A late frame from a superseded declaration can never mutate a newer run. */
    val superseded: Boolean = false,
) {
    enum class Type {
        MESSAGE, TOOL_CALL, TOOL_RESULT, PERMISSION, QUESTION,
        RUN_COMPLETE, ENGINE_STATE, OTHER,
    }
}

data class ExecutionView(
    val executionId: String,
    val runId: String,
    val target: ExecutionTarget,
    val lifecycle: ExecutionLifecycle,
    val interaction: InteractionState,
    val observation: ObservationState,
    val cancellation: CancellationState,
    val epoch: Long,
    val lastObservedProgressAt: Long,
    val reasonCode: String? = null,
    val retrySourceExecutionId: String? = null,
    val provenanceGap: Boolean = false,
    /** True only for an outcome adopted from startup reconciliation. */
    val recovered: Boolean = false,
) {
    val isTerminal: Boolean
        get() = lifecycle == ExecutionLifecycle.UNSENT ||
            lifecycle == ExecutionLifecycle.SEND_AMBIGUOUS ||
            lifecycle == ExecutionLifecycle.COMPLETED ||
            lifecycle == ExecutionLifecycle.FAILED ||
            lifecycle == ExecutionLifecycle.LOST ||
            lifecycle == ExecutionLifecycle.UNKNOWN

    /**
     * W1: the approved lifecycle keeps confirmed cancellation in COMPLETED,
     * but that category must never be presented as success. The machine-readable
     * distinction is the terminal reason, not a new phase value.
     */
    val isCancelled: Boolean
        get() = lifecycle == ExecutionLifecycle.COMPLETED &&
            reasonCode == "confirmed_cancelled"
}

/**
 * V2-16 A2 Stage 2: a classified durable-event outcome. A bare affected-row
 * count cannot distinguish a duplicate from a stale generation or a DB failure;
 * replay must make that distinction without weakening any DAO guard.
 */
sealed interface ConfirmedApply {
    data object Applied : ConfirmedApply
    data object AlreadyApplied : ConfirmedApply
    /** A valid event needing no write, e.g. progress on an already-RUNNING row. */
    data object NotApplicable : ConfirmedApply
    /** A prerequisite (durable ACK) is not yet satisfied. */
    data object Deferred : ConfirmedApply
    data object Stale : ConfirmedApply
    /** Incompatible terminal evidence; never overwrite. */
    data object Conflict : ConfirmedApply
    data object IntegrityFailure : ConfirmedApply
    data class Unavailable(val cause: Throwable) : ConfirmedApply
}

/** Result of a provisional attachment commit. */
sealed class CommitResult {
    object Committed : CommitResult()
    data class Rejected(val reason: String) : CommitResult()
}
