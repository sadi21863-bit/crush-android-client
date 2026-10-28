package com.crush.mobile.data.crush

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the 404 that made every send fail.
 *
 * Crush reaps an unattached workspace in about 30 seconds (measured on the
 * reference device), so a workspace id cached at bootstrap is normally already
 * dead by the time the user hits send. These tests pin the decision that the
 * send path uses to decide whether the cached id can be trusted.
 *
 * The bias is deliberate: a redundant re-attach costs one idempotent POST,
 * whereas trusting a stale id silently swallows the user's message.
 */
class WorkspaceLeaseTest {

    @Test
    fun `just attached is fresh`() {
        assertTrue(WorkspaceLease.isFresh(lastAttachedAtMs = 1_000L, nowMs = 1_000L))
    }

    @Test
    fun `fresh just inside the window`() {
        assertTrue(WorkspaceLease.isFresh(lastAttachedAtMs = 0L, nowMs = 14_999L))
    }

    @Test
    fun `stale past the measured reap boundary`() {
        assertFalse(WorkspaceLease.isFresh(lastAttachedAtMs = 0L, nowMs = 30_000L))
    }

    @Test
    fun `unknown attach time reattaches`() {
        // Fails closed: we cannot prove freshness, so we re-attach.
        assertFalse(WorkspaceLease.isFresh(lastAttachedAtMs = null, nowMs = 5_000L))
    }

    @Test
    fun `backwards clock reattaches`() {
        assertFalse(WorkspaceLease.isFresh(lastAttachedAtMs = 10_000L, nowMs = 9_000L))
    }

    @Test
    fun `zero window always reattaches`() {
        assertFalse(WorkspaceLease.isFresh(lastAttachedAtMs = 5_000L, nowMs = 5_000L, freshSec = 0))
    }

    @Test
    fun `window is shorter than the reap so a fresh id cannot expire mid-send`() {
        // If FRESH_SEC ever reached the ~30s reap, a user could pause between the
        // freshness check and the POST and 404 anyway.
        assertTrue(
            "FRESH_SEC must stay well inside the 30s reap window",
            WorkspaceLease.FRESH_SEC <= 20
        )
    }

    @Test
    fun `workspace-gone gets a human explanation`() {
        assertEquals(
            "The engine released the workspace. Retrying...",
            WorkspaceLease.explainReattach(
                "HTTP 404 on /v1/workspaces/x/agent: {\"message\":\"workspace not found\"}"
            )
        )
    }

    @Test
    fun `other errors are not mislabelled as a workspace problem`() {
        assertEquals("Send failed", WorkspaceLease.explainReattach("HTTP 500 internal"))
        assertEquals("Send failed", WorkspaceLease.explainReattach(null))
    }

    @Test
    fun `workspace-not-found is matched case-insensitively`() {
        assertEquals(
            "The engine released the workspace. Retrying...",
            WorkspaceLease.explainReattach("WORKSPACE NOT FOUND")
        )
    }

    // ---------------------------------------------------------------------
    // Engine-generation rule (P1-11), wired from AppContainer.
    //
    // Pure companion state only: WorkspaceManager itself lives behind a
    // network client and is not constructible for a JVM unit test here, so the
    // trust rule and the counter's monotonicity are pinned in isolation. If
    // the rule ever differs between this test and liveIdOrNull(), the send
    // path's generation gate exists in name only.
    // ---------------------------------------------------------------------

    @Test
    fun `a restart moves the generation monotonically`() {
        // Bumps a process-global companion counter; no other test in this
        // class (or suite) trusts absolute values - the rule is equality on
        // the minting generation - so a bump here stays inert.
        val before = WorkspaceManager.currentEngineGeneration()
        WorkspaceManager.onEngineRestarted()
        assertTrue(
            "a restart must move the generation forward",
            WorkspaceManager.currentEngineGeneration() > before
        )
    }

    @Test
    fun `an id minted by the PREVIOUS engine run is dead on arrival even if fresh`() {
        WorkspaceManager.onEngineRestarted()
        val current = WorkspaceManager.currentEngineGeneration()
        assertFalse(
            WorkspaceManager.isIdTrustworthy(current - 1, current)
        )
    }

    @Test
    fun `an id minted by the CURRENT engine run is the only trustworthy one`() {
        WorkspaceManager.onEngineRestarted()
        val current = WorkspaceManager.currentEngineGeneration()
        assertTrue(WorkspaceManager.isIdTrustworthy(current, current))
    }

    @Test
    fun `unknown minting generation fails closed`() {
        WorkspaceManager.onEngineRestarted()
        assertFalse(
            "no provable generation means re-attach, matching WorkspaceLease's fail-closed rule",
            WorkspaceManager.isIdTrustworthy(null, WorkspaceManager.currentEngineGeneration())
        )
    }
}
