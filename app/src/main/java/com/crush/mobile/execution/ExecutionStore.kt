package com.crush.mobile.execution

/**
 * V2-16 A2 slice 1: the durable half of execution ownership.
 *
 * The [ExecutionController] stays the in-process owner and performs no I/O;
 * this store is a narrow port the send path calls at the four crash
 * boundaries. Every write is guarded by the DAO's expected-phase predicate,
 * so a late writer cannot clobber a newer phase. No prompt text, transcript or
 * credential is ever passed here.
 */
class ExecutionStore(
    private val dao: ExecutionDao,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * Boundary 1: the ONLY row creation. Called before the HTTP POST, so an
     * engine acceptance can never leave an execution with no durable record.
     * The row starts as a pure intent - it does not claim the send happened.
     */
    suspend fun persistIntent(
        executionId: String,
        runId: String,
        target: ExecutionTarget,
        engineKeyToken: String,
        workspacePath: String,
        generation: Long? = null,
        leaseEpochMs: Long? = null,
    ): ExecutionEntity {
        val now = clock()
        val row = ExecutionEntity(
            executionId = executionId,
            runId = runId,
            sessionId = target.sessionId,
            engineKeyToken = engineKeyToken,
            workspacePath = workspacePath,
            engineGeneration = generation,
            leaseEpochMs = leaseEpochMs,
            phase = ExecutionLifecycle.INTENT_RECORDED.name,
            progressAtMs = now,
            createdAtMs = now,
            updatedAtMs = now,
            promptRef = null, // A3 owns prompt identity; slice 1 never writes it.
            ackState = ExecutionAckState.INTENT_ONLY.name,
        )
        dao.insert(row)
        return row
    }

    /** Boundary 1→2: the send is about to be attempted. */
    suspend fun markSent(id: String) = dao.markSent(id, clock())

    /** Boundary 3: a definitive acknowledgement was recorded. */
    suspend fun markAcked(id: String) = dao.markAcked(id, clock())

    /**
     * Boundary 2→3 with a lost acknowledgement. Terminal for automation: the
     * app must surface it, never re-send it.
     */
    suspend fun markAmbiguous(id: String) = dao.markAmbiguous(id, clock())

    /** Definitive non-retryable rejection. */
    suspend fun markRejected(id: String, reason: String) =
        dao.markRejected(id, reason, clock())

    /** Only a confirmed wire event may advance the stored phase to RUNNING. */
    suspend fun markRunning(id: String) = dao.markRunning(id, clock())

    /**
     * Confirmed wire frames identify a run, not an execution. Called from the
     * production adapter callback; the DAO guard makes repeat events harmless.
     */
    suspend fun markRunningForRun(runId: String) = dao.markRunningByRunId(runId, clock())

    suspend fun byRunId(runId: String): ExecutionEntity? = dao.byRunId(runId)

    /** Named, evidence-backed unknown state; never a fabricated terminal. */
    suspend fun markUnknown(id: String, reason: String) = dao.markUnknown(id, reason, clock())

    /**
     * V2-16 A2 Stage 2: the classified durable-event path. Every write is
     * keyed by (executionId, runId, generation) — never by run id alone. A
     * zero-row DAO result is classified by re-reading the exact row; it is
     * never silently treated as either success or a generic failure.
     */
    suspend fun applyConfirmedEvent(event: ExecutionEvent): ConfirmedApply {
        val executionId = event.executionId ?: return ConfirmedApply.Stale
        val runId = event.runId ?: return ConfirmedApply.Stale
        val generation = event.generation ?: return ConfirmedApply.Stale
        // A superseded declaration is read-only for late events.
        if (event.superseded) return ConfirmedApply.Stale

        val row = runCatching { dao.byExecutionAndRun(executionId, runId) }
            .getOrElse { return ConfirmedApply.Unavailable(it) }
            ?: return ConfirmedApply.Conflict
        if (row.engineGeneration == null || row.engineGeneration != generation) {
            return ConfirmedApply.Stale
        }
        if (row.ackState != ExecutionAckState.ACKED.name) {
            return if (row.phase == ExecutionLifecycle.INTENT_RECORDED.name ||
                row.phase == ExecutionLifecycle.SEND_ATTEMPTED.name
            ) ConfirmedApply.Deferred else ConfirmedApply.NotApplicable
        }

        val isTerminal = event.type == ExecutionEvent.Type.RUN_COMPLETE
        if (row.isTerminalPhase()) {
            // A late progress frame is harmless; a second terminal fact is a
            // duplicate, never a rewrite of reason or timestamp.
            return if (isTerminal) ConfirmedApply.AlreadyApplied else ConfirmedApply.NotApplicable
        }
        // Healthy progress on a row already RUNNING needs no persistence. It
        // must NEVER take the deferred/buffer path and overflow into a false
        // UNKNOWN — this is a distinct, non-error classification.
        if (!isTerminal && row.phase == ExecutionLifecycle.RUNNING.name) {
            return ConfirmedApply.NotApplicable
        }

        val result = runCatching {
            if (isTerminal) {
                val reason = event.detail?.takeIf { it.isNotBlank() } ?: "confirmed_completion"
                dao.markTerminalExact(
                    executionId,
                    runId,
                    generation,
                    ExecutionLifecycle.COMPLETED.name,
                    reason,
                    clock(),
                )
            } else {
                dao.markRunningExact(executionId, runId, generation, clock())
            }
        }.getOrElse { return ConfirmedApply.Unavailable(it) }
        return classifyCount(result, executionId, runId, generation, isTerminal)
    }

    /**
     * W2: a confirmed error from the per-turn stream, where identity is
     * explicit and validated. Never called from the adapter's anonymous path.
     */
    suspend fun applyConfirmedFailure(
        executionId: String,
        runId: String,
        generation: Long,
        reason: String,
    ): ConfirmedApply {
        val row = runCatching { dao.byExecutionAndRun(executionId, runId) }
            .getOrElse { return ConfirmedApply.Unavailable(it) }
            ?: return ConfirmedApply.Conflict
        if (row.engineGeneration == null || row.engineGeneration != generation) {
            return ConfirmedApply.Stale
        }
        if (row.phase == ExecutionLifecycle.FAILED.name) return ConfirmedApply.AlreadyApplied
        if (row.phase == ExecutionLifecycle.COMPLETED.name &&
            row.terminalReason == "confirmed_cancelled"
        ) return ConfirmedApply.Conflict
        if (row.phase == ExecutionLifecycle.COMPLETED.name &&
            row.terminalReason != "confirmed_completion"
        ) return ConfirmedApply.Conflict

        val result = runCatching {
            if (row.phase == ExecutionLifecycle.COMPLETED.name) {
                dao.correctGenericCompletionToFailed(
                    executionId, runId, generation, reason, clock(),
                )
            } else if (row.ackState == ExecutionAckState.ACKED.name) {
                dao.markFailedExact(executionId, runId, generation, reason, clock())
            } else {
                return ConfirmedApply.Deferred
            }
        }.getOrElse { return ConfirmedApply.Unavailable(it) }
        return classifyCount(result, executionId, runId, generation, terminal = true)
    }

    private suspend fun classifyCount(
        count: Int,
        executionId: String,
        runId: String,
        generation: Long,
        terminal: Boolean,
    ): ConfirmedApply = when {
        count == 1 -> ConfirmedApply.Applied
        count > 1 -> ConfirmedApply.IntegrityFailure
        else -> {
            val current = runCatching { dao.byExecutionAndRun(executionId, runId) }
                .getOrElse { return ConfirmedApply.Unavailable(it) }
                ?: return ConfirmedApply.Conflict
            if (current.engineGeneration == null || current.engineGeneration != generation) {
                ConfirmedApply.Stale
            } else if (current.isTerminalPhase()) {
                if (terminal) ConfirmedApply.AlreadyApplied else ConfirmedApply.NotApplicable
            } else if (current.ackState != ExecutionAckState.ACKED.name) {
                ConfirmedApply.Deferred
            } else {
                ConfirmedApply.Conflict
            }
        }
    }

    private fun ExecutionEntity.isTerminalPhase(): Boolean = when (phase) {
        ExecutionLifecycle.UNSENT.name,
        ExecutionLifecycle.SEND_AMBIGUOUS.name,
        ExecutionLifecycle.COMPLETED.name,
        ExecutionLifecycle.FAILED.name,
        ExecutionLifecycle.LOST.name,
        ExecutionLifecycle.UNKNOWN.name,
        -> true
        else -> false
    }

    suspend fun markTerminal(id: String, phase: ExecutionLifecycle, reason: String?) =
        dao.markTerminal(id, phase.name, reason, clock())

    suspend fun observeProgress(id: String) = dao.observeProgress(id, clock())

    /** C1/C2/C3 recovery inputs: rows that were not terminal at death. */
    suspend fun recoverableRows(): List<ExecutionEntity> = dao.recoverableRows()

    suspend fun byId(id: String): ExecutionEntity? = dao.byId(id)

    /**
     * Recovery outcome for an intent that never reached a definitive ack.
     * Returns the affected-row count: 0 means a newer writer already settled
     * the row, and the caller must not claim this outcome.
     */
    suspend fun markUnsent(id: String, reason: String) =
        dao.markUnsent(id, reason, clock())

    /** Fail-closed C2/C3 outcome when no atomic attach can be proven. */
    suspend fun markLost(id: String, reason: String) =
        dao.markLost(id, reason, clock())
}
