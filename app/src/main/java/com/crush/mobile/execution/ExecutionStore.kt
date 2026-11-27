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
