package com.crush.mobile.ui.screens.chat

/**
 * V2-15 Phase E remediation: the operation currently in flight for a question
 * batch. Exposed to the dialog so both buttons can be disabled while a POST is
 * unwinding.
 */
enum class QuestionOperation { SUBMIT, CANCEL }

/** Exact identity of the displayed question batch. */
internal data class QuestionRef(
    val questionId: String,
    val batchId: String?,
)

/** A claimed operation. The token makes release idempotent and ownership-safe. */
internal data class QuestionOperationLease(
    val ref: QuestionRef,
    val operation: QuestionOperation,
    val token: Long,
)

/**
 * Small lifecycle seam for A12 question operations.
 *
 * `claim()` is synchronous and atomic, so a double tap cannot enqueue two
 * POSTs. The guard is per question identity, not global: a new batch can be
 * operated while an older batch's request is still unwinding, and an old
 * completion cannot release the new batch's guard.
 *
 * `execute()` owns the identity-checked state transitions:
 * - success clears only the batch it claimed;
 * - failure sets the error only on that batch;
 * - completion clears the in-flight state only on that batch;
 * - cancellation releases the guard in `finally`.
 */
internal class QuestionOperationController(
    private val current: () -> QuestionRef?,
    private val onOperation: (QuestionRef, QuestionOperation?) -> Unit,
    private val onError: (QuestionRef, String?) -> Unit,
    private val onClear: (QuestionRef) -> Unit,
) {

    private val active = HashMap<String, QuestionOperationLease>()
    private var nextToken = 0L

    /** Claims synchronously; returns null when this batch already has an operation. */
    @Synchronized
    fun claim(ref: QuestionRef, operation: QuestionOperation): QuestionOperationLease? {
        val key = keyOf(ref)
        if (active.containsKey(key)) return null
        val lease = QuestionOperationLease(ref, operation, ++nextToken)
        active[key] = lease
        if (current() == ref) onOperation(ref, operation)
        return lease
    }

    /**
     * Runs one already-claimed operation. [block] returns null on success or a
     * user-visible error on failure.
     */
    suspend fun execute(
        lease: QuestionOperationLease,
        block: suspend () -> String?,
    ) {
        try {
            val failure = block()
            if (current() == lease.ref) {
                if (failure == null) onClear(lease.ref)
                else onError(lease.ref, failure)
            }
        } finally {
            if (current() == lease.ref) onOperation(lease.ref, null)
            release(lease)
        }
    }

    @Synchronized
    private fun release(lease: QuestionOperationLease) {
        val key = keyOf(lease.ref)
        if (active[key]?.token == lease.token) active.remove(key)
    }

    private fun keyOf(ref: QuestionRef): String =
        ref.questionId + "\u0000" + (ref.batchId ?: "")
}
