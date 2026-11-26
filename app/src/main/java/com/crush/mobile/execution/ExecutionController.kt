package com.crush.mobile.execution

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A1 process-scoped execution owner.
 *
 * Scope: in-process execution identity, the four independent state dimensions,
 * explicit intents, observation subscription, and keyed invalidation. There is
 * NO durable record yet: the "durable intent before send" guarantee belongs to
 * A2. Until then a started execution is in-memory only and must not be
 * described as recoverable across process death.
 *
 * The controller never starts, stops, restarts or kills an engine, never
 * writes Compose state, and never cancels on a screen leaving. Cancellation is
 * an explicit intent; subscribe/unsubscribe only change observation state.
 */
class ExecutionController(
    private val epochOf: (String) -> Long = { 0L },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val records = ConcurrentHashMap<String, ExecutionView>()
    private val activeByKey = ConcurrentHashMap<String, MutableSet<String>>()
    private val observerEpochs = ConcurrentHashMap<String, Long>()
    private val _executions = MutableStateFlow<Map<String, ExecutionView>>(emptyMap())
    val executions: StateFlow<Map<String, ExecutionView>> = _executions

    private fun publish() {
        _executions.value = records.toMap()
    }

    private fun mutate(id: String, block: (ExecutionView) -> ExecutionView): Boolean {
        synchronized(records) {
            val current = records[id] ?: return false
            if (current.isTerminal) return false
            records[id] = block(current)
        }
        publish()
        return true
    }

    /** Mint a new execution. In-memory intent only until A2. */
    fun start(target: ExecutionTarget, runId: String = UUID.randomUUID().toString()): ExecutionView {
        val id = UUID.randomUUID().toString()
        val view = ExecutionView(
            executionId = id,
            runId = runId,
            target = target,
            lifecycle = ExecutionLifecycle.INTENT_RECORDED,
            interaction = InteractionState.NONE,
            observation = ObservationState.DETACHED,
            cancellation = CancellationState.NOT_REQUESTED,
            epoch = epochOf(target.instanceKey),
            lastObservedProgressAt = clock(),
        )
        records[id] = view
        activeByKey.computeIfAbsent(target.instanceKey) { mutableSetOf() }.add(id)
        publish()
        return view
    }

    /**
     * Registers the intent and the pre-send attempt marker BEFORE the HTTP
     * call. A1 has no durable store: this is in-memory ordering, not crash
     * consistency (A2). It prevents "engine accepted, app has no record" in
     * the live process and never pretends the POST succeeded.
     */
    fun onIntent(target: ExecutionTarget, runId: String): ExecutionView {
        val view = start(target, runId)
        markAttempt(view.executionId)
        return records[view.executionId]!!
    }

    fun markAttempt(id: String): Boolean =
        mutate(id) { it.copy(lifecycle = ExecutionLifecycle.SEND_ATTEMPTED) }

    /** Definitive rejection only. Never inferred from a timeout or reset. */
    fun markSubmitRejected(id: String, reasonCode: String): Boolean =
        markTerminal(id, ExecutionLifecycle.FAILED, reasonCode)

    /** Ambiguous POST outcome: terminal UNKNOWN, no automatic retry. */
    fun markAmbiguousSend(id: String, reasonCode: String): Boolean =
        markTerminal(id, ExecutionLifecycle.UNKNOWN, reasonCode)

    fun markSubmissionAcknowledged(id: String): Boolean =
        mutate(id) { it.copy(lifecycle = ExecutionLifecycle.SUBMISSION_ACKNOWLEDGED) }

    /** Only a Confirmed event may advance to RUNNING. */
    fun markRunning(id: String, attribution: Attribution): Boolean {
        if (attribution != Attribution.CONFIRMED) return false
        return mutate(id) { it.copy(lifecycle = ExecutionLifecycle.RUNNING) }
    }

    /** Plausible activity updates observation freshness only, never phase. */
    fun observeProgress(id: String, attribution: Attribution, at: Long = clock()): Boolean {
        if (attribution == Attribution.UNATTRIBUTED) return false
        return mutate(id) { it.copy(lastObservedProgressAt = at) }
    }

    fun setNoObservableProgress(id: String): Boolean =
        mutate(id) { it.copy(observation = ObservationState.NO_OBSERVABLE_PROGRESS) }

    fun setInteraction(id: String, state: InteractionState, attribution: Attribution): Boolean {
        if (state != InteractionState.NONE && attribution != Attribution.CONFIRMED) return false
        return mutate(id) { it.copy(interaction = state) }
    }

    /** Resolving a prompt clears interaction only; it never implies resumed execution. */
    fun resolvePrompt(id: String): Boolean =
        mutate(id) { it.copy(interaction = InteractionState.NONE) }

    fun requestCancel(id: String, reasonCode: String): Boolean =
        mutate(id) {
            it.copy(
                lifecycle = ExecutionLifecycle.CANCELLING,
                cancellation = CancellationState.REQUESTED,
                reasonCode = reasonCode,
            )
        }

    /** `resolved` is a request fact, never an execution-terminal fact. */
    fun markCancelResolved(id: String): Boolean =
        mutate(id) { it.copy(cancellation = CancellationState.REQUEST_RESOLVED) }

    fun markTerminal(id: String, lifecycle: ExecutionLifecycle, reasonCode: String? = null): Boolean {
        if (!lifecycle.isTerminal()) return false
        return mutate(id) { it.copy(lifecycle = lifecycle, reasonCode = reasonCode) }
    }

    fun markUnknown(id: String, reasonCode: String): Boolean =
        markTerminal(id, ExecutionLifecycle.UNKNOWN, reasonCode)

    /** Retry is always a NEW execution linked to the old one. */
    fun retry(id: String): ExecutionView? {
        val old = synchronized(records) { records[id] ?: return null }
        val fresh = start(
            target = old.target,
            runId = UUID.randomUUID().toString(),
        )
        synchronized(records) {
            records[fresh.executionId] = fresh.copy(retrySourceExecutionId = id)
        }
        publish()
        return records[fresh.executionId]
    }

    fun subscribe(id: String, ownerEpoch: Long): Flow<ExecutionView> {
        observerEpochs[id] = ownerEpoch
        mutate(id) { it.copy(observation = ObservationState.OBSERVING) }
        return executions.map { it[id] }.filterNotNull()
    }

    /** Detach only. It never cancels, fails, or loses an execution. */
    fun unsubscribe(id: String, ownerEpoch: Long) {
        if (observerEpochs[id] != ownerEpoch) return
        observerEpochs.remove(id)
        mutate(id) { it.copy(observation = ObservationState.DETACHED) }
    }

    /** Called from a destroyed screen: observation only, never cancellation. */
    fun unsubscribeOwner(ownerEpoch: Long) {
        observerEpochs.filter { it.value == ownerEpoch }.keys.toList().forEach { id ->
            observerEpochs.remove(id)
            mutate(id) { it.copy(observation = ObservationState.DETACHED) }
        }
    }

    fun hasActive(instanceKey: String): Boolean =
        activeByKey[instanceKey]?.any { id -> records[id]?.isTerminal == false } == true

    /** Called by the container's invalidation listener. Never claims completion. */
    fun onInvalidated(instanceKey: String, source: String) {
        // A1 device evidence: a production invalidation route actually reached
        // the controller. No prompt, transcript or credential is logged.
        com.crush.mobile.util.AppLog.i(
            "ExecutionController",
            "invalidated key=$instanceKey source=$source epoch=${epochOf(instanceKey)}",
        )
        val ids = activeByKey[instanceKey]?.toList() ?: return
        ids.forEach { id ->
            mutate(id) {
                it.copy(
                    epoch = epochOf(instanceKey),
                    lifecycle = ExecutionLifecycle.UNKNOWN,
                    reasonCode = "invalidated:$source",
                )
            }
        }
    }

    private fun ExecutionLifecycle.isTerminal(): Boolean = when (this) {
        ExecutionLifecycle.UNSENT, ExecutionLifecycle.COMPLETED,
        ExecutionLifecycle.FAILED, ExecutionLifecycle.LOST, ExecutionLifecycle.UNKNOWN -> true
        else -> false
    }
}
