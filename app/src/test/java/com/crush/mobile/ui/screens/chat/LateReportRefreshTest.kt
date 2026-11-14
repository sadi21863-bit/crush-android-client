package com.crush.mobile.ui.screens.chat

import com.crush.mobile.data.crush.SubAgentReport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The late-report refresh decision (device finding 2026-11-14).
 *
 * A detached sub-agent's report is persisted only after the dispatching
 * turn's run has closed, so the live screen misses it until a reopen. The
 * bounded refresh re-reads the session; these pins keep it from becoming a
 * transcript rewriter:
 *
 *  1. a transcript with no report is never replaced (normal turns untouched);
 *  2. a newly persisted report DOES replace the live rows;
 *  3. an identical replay is a no-op (no pointless churn every poll);
 *  4. the primary agent's wake reply, persisted after the report, still
 *     refreshes - partial and final text alike.
 */
class LateReportRefreshTest {

    private fun reportRow(
        id: String,
        label: String = "probe",
        output: String = "PONG"
    ) = ChatMessage(
        id = id,
        role = "user",
        text = "",
        reports = listOf(SubAgentReport(label = label, output = output))
    )

    private fun assistant(id: String, text: String) =
        ChatMessage(id = id, role = "assistant", text = text)

    private fun user(id: String, text: String) =
        ChatMessage(id = id, role = "user", text = text)

    @Test
    fun `a transcript with no report is never replaced`() {
        val live = listOf(user("live-u", "hello"), assistant("live-a", "hi"))
        val replay = listOf(user("h-u-1", "hello"), assistant("h-a-1", "hi"))
        assertFalse(
            "a normal turn must keep its live rows even when ids differ",
            lateReportRefreshDue(live, replay)
        )
    }

    @Test
    fun `a newly persisted report replaces the live transcript`() {
        val live = listOf(
            user("live-u", "dispatch a sub-agent"),
            assistant("live-a", "waiting for its report")
        )
        val replay = listOf(
            user("h-u-1", "dispatch a sub-agent"),
            reportRow("h-u-2"),
            assistant("h-a-1", "DONE")
        )
        assertTrue(
            "the report row the open screen lacks must be written",
            lateReportRefreshDue(live, replay)
        )
    }

    @Test
    fun `an identical replay is a no-op`() {
        val replay = listOf(reportRow("h-u-2"), assistant("h-a-1", "DONE"))
        assertFalse(
            "the same persisted state must not be rewritten every poll",
            lateReportRefreshDue(replay, replay.toList())
        )
    }

    @Test
    fun `the wake reply arriving after the report still refreshes`() {
        val reportOnly = listOf(reportRow("h-u-2"))
        assertTrue(lateReportRefreshDue(reportOnly, reportOnly + assistant("h-a-1", "DONE")))
        assertTrue(
            "a partially persisted wake reply is still fresher than the report alone",
            lateReportRefreshDue(reportOnly, reportOnly + assistant("h-a-1", "DON"))
        )
    }
}
