package com.crush.mobile.execution

/**
 * V2-16 A2 slice 1 startup reconciliation for run cells C1–C3 and the four
 * send-path crash boundaries of design §6.
 *
 * Deliberately FAIL CLOSED. §7 / §17.1 forbid attaching a recovered execution
 * unless validation and attach happen in the same critical section that guards
 * engine replacement. That path-lock wrapper does not exist yet, and the
 * engine generation counter is process-local, so a persisted generation cannot
 * be compared with the new process's counter. Therefore this reconciler never
 * claims a live reattach: it produces a named, visible outcome instead.
 *
 * No prompt policy is implemented: rows already in AMBIGUOUS (C6, D3/A3) are
 * left exactly as they are, never auto-resolved or auto-re-sent.
 */
class ExecutionReconciler(
    private val dao: ExecutionDao,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    enum class Outcome { UNSENT, AMBIGUOUS, LOST, DEFERRED }

    data class Recovered(
        val entity: ExecutionEntity,
        val outcome: Outcome,
        val detail: String,
    )

    data class Summary(val recovered: List<Recovered>) {
        val unsent: Int get() = recovered.count { it.outcome == Outcome.UNSENT }
        val ambiguous: Int get() = recovered.count { it.outcome == Outcome.AMBIGUOUS }
        val lost: Int get() = recovered.count { it.outcome == Outcome.LOST }
        val deferred: Int get() = recovered.count { it.outcome == Outcome.DEFERRED }
    }

    /**
     * C1 (idle) is represented by an empty [Summary]: no record is minted, no
     * session is created, and the controller stays untouched.
     */
    suspend fun reconcile(): Summary {
        val now = clock()
        val recovered = mutableListOf<Recovered>()
        dao.recoverableRows().forEach { row ->
            val entry = when {
                // A persisted intent whose send was never attempted (boundary 1).
                row.phase == ExecutionLifecycle.INTENT_RECORDED.name ||
                    row.ackState == ExecutionAckState.INTENT_ONLY.name -> {
                    val detail = "recovery:send_not_attempted"
                    val n = dao.markUnsent(row.executionId, detail, now)
                    Recovered(row, if (n > 0) Outcome.UNSENT else Outcome.DEFERRED, detail)
                }

                // C6 / prompt ambiguity is out of slice-1 scope: leave untouched.
                row.ackState == ExecutionAckState.AMBIGUOUS.name ->
                    Recovered(row, Outcome.DEFERRED, "recovery:ambiguous_prompt_deferred_to_D3_A3")

                // Boundary 2→3: a send with no recorded acknowledgement. We
                // cannot query engine history from the container, so we record
                // ambiguity and never auto-resend.
                row.ackState == ExecutionAckState.SENT_UNCONFIRMED.name -> {
                    val detail = "recovery:ack_unknown_no_auto_resend"
                    val n = dao.markAmbiguous(row.executionId, now)
                    Recovered(row, if (n > 0) Outcome.AMBIGUOUS else Outcome.DEFERRED, detail)
                }

                // ACKED / RUNNING / CANCELLING: §7 forbids an unvalidated
                // attach, so the honest outcome is a named loss, not a claim.
                else -> {
                    val detail = "recovery:no_atomic_attach"
                    val n = dao.markLost(row.executionId, detail, now)
                    Recovered(row, if (n > 0) Outcome.LOST else Outcome.DEFERRED, detail)
                }
            }
            recovered += entry
        }
        return Summary(recovered)
    }
}
