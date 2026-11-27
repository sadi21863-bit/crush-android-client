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
}

/** Result of a provisional attachment commit. */
sealed class CommitResult {
    object Committed : CommitResult()
    data class Rejected(val reason: String) : CommitResult()
}
