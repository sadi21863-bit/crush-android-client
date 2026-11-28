package com.crush.mobile.execution

/**
 * V2-16 A2 slice 1 startup reconciliation for run cells C1–C3 and the four
 * send-path crash boundaries of design §6.
 *
 * Remediated semantics: an inability to prove a safe reattach is NOT proof of
 * loss. Evidence gaps are named UNKNOWN, never LOST. LOST is reserved for a
 * successfully completed probe that confirms absence (design §9), and prompt
 * ambiguity is left to D3/A3 rather than being invented here.
 */
class ExecutionReconciler(
    private val dao: ExecutionDao,
    private val evidence: ExecutionEvidencePort = UnavailableEngineEvidence,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    enum class Outcome { UNSENT, AMBIGUOUS, UNKNOWN, LOST, COMPLETED, FAILED, DEFERRED }

    data class Recovered(
        val entity: ExecutionEntity,
        val outcome: Outcome,
        val detail: String,
        val evidence: ReconciliationResult = ReconciliationResult.UNAVAILABLE,
    )

    data class Summary(val recovered: List<Recovered>) {
        val unsent: Int get() = recovered.count { it.outcome == Outcome.UNSENT }
        val ambiguous: Int get() = recovered.count { it.outcome == Outcome.AMBIGUOUS }
        val unknown: Int get() = recovered.count { it.outcome == Outcome.UNKNOWN }
        val lost: Int get() = recovered.count { it.outcome == Outcome.LOST }
        val completed: Int get() = recovered.count { it.outcome == Outcome.COMPLETED }
        val failed: Int get() = recovered.count { it.outcome == Outcome.FAILED }
        val deferred: Int get() = recovered.count { it.outcome == Outcome.DEFERRED }
    }

    /** C1 (idle) is an empty Summary: no record is minted, no session created. */
    suspend fun reconcile(): Summary {
        val now = clock()
        val recovered = mutableListOf<Recovered>()
        dao.recoverableRows().forEach { row ->
            recovered += decide(row, now)
        }
        return Summary(recovered)
    }

    private suspend fun decide(row: ExecutionEntity, now: Long): Recovered {
        // A prior reconciliation already named this outcome. Re-adopt it
        // unchanged: this is what makes outcomes stable across restarts.
        if (row.phase == ExecutionLifecycle.UNKNOWN.name) {
            return Recovered(
                row,
                Outcome.UNKNOWN,
                row.terminalReason ?: "recovery:unknown",
            )
        }

        // Boundary 1: a persisted intent whose send was never started.
        if (row.phase == ExecutionLifecycle.INTENT_RECORDED.name ||
            row.ackState == ExecutionAckState.INTENT_ONLY.name
        ) {
            val detail = "recovery:send_not_attempted"
            val n = dao.markUnsent(row.executionId, detail, now)
            return Recovered(row, if (n > 0) Outcome.UNSENT else Outcome.UNKNOWN, detail)
        }

        // Run-side send ambiguity is identified by ack_state, NOT by a prompt.
        // prompt_ref is reserved for A3 and slice 1 never writes it.
        if (row.ackState == ExecutionAckState.AMBIGUOUS.name) {
            return if (row.promptRef == null) {
                Recovered(row, Outcome.AMBIGUOUS, "recovery:send_ambiguous")
            } else {
                Recovered(row, Outcome.DEFERRED, "recovery:prompt_ambiguous_deferred_to_D3_A3")
            }
        }

        // Boundary 2→3: a send with no recorded ack. Never auto-resend.
        if (row.ackState == ExecutionAckState.SENT_UNCONFIRMED.name) {
            // Same stable detail as an already-AMBIGUOUS row: the name must
            // not change between the first and later startup cycles.
            val detail = "recovery:send_ambiguous"
            val n = dao.markAmbiguous(row.executionId, now)
            return Recovered(row, if (n > 0) Outcome.AMBIGUOUS else Outcome.UNKNOWN, detail)
        }

        // ACKED / RUNNING / CANCELLING: ask for evidence instead of assuming.
        val ev = runCatching {
            evidence.probe(row.engineKeyToken, row.workspacePath, row.sessionId, row.engineGeneration)
        }.getOrDefault(
            EngineEvidence(
                result = ReconciliationResult.UNAVAILABLE,
                detail = "evidence probe failed",
            )
        )
        return when (ev.result) {
            ReconciliationResult.CONFIRMED -> {
                val terminal = ev.terminalLifecycle
                if (terminal == ExecutionLifecycle.COMPLETED ||
                    terminal == ExecutionLifecycle.FAILED
                ) {
                    val outcome = if (terminal == ExecutionLifecycle.FAILED) {
                        Outcome.FAILED
                    } else {
                        Outcome.COMPLETED
                    }
                    val n = dao.markTerminal(row.executionId, terminal.name, ev.detail, now)
                    Recovered(row, if (n > 0) outcome else Outcome.UNKNOWN, ev.detail, ev.result)
                } else if (terminal != null) {
                    // A confirmed-live execution cannot be honestly rendered
                    // as LOST and cannot be claimed as resumed without the
                    // §7 atomic attach wrapper. Keep UNKNOWN.
                    val detail = "recovery:confirmed_running_unattachable"
                    val n = dao.markUnknown(row.executionId, detail, now)
                    Recovered(row, Outcome.UNKNOWN, detail, ev.result)
                } else {
                    // All required sources were queried successfully and no
                    // live execution exists: the only evidence-backed loss.
                    val detail = "recovery:confirmed_absence"
                    val n = dao.markLost(row.executionId, detail, now)
                    Recovered(row, if (n > 0) Outcome.LOST else Outcome.UNKNOWN, detail, ev.result)
                }
            }
            else -> {
                val detail = "recovery:evidence_${ev.result.name.lowercase()}"
                val n = dao.markUnknown(row.executionId, detail, now)
                Recovered(row, if (n > 0) Outcome.UNKNOWN else Outcome.UNKNOWN, detail, ev.result)
            }
        }
    }
}
