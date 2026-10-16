package com.crush.mobile.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The shipped bug this file exists to prevent (audit P1-07):
 *
 * `setProvider` applied the switch immediately, whatever was running. Applied
 * mid-turn it dropped the workspace id and cached key under a streaming run,
 * stopped the shared SSE connection that run was consuming, and cleared both
 * model slots - the in-flight reply died silently while the UI still showed
 * its bubble.
 *
 * These tests pin the serialisation rule: a switch during a run defers; the
 * already-active provider reverts and cancels a deferred switch; a switch
 * without a run applies. The decision lives in [ProviderSwitchPolicy] because
 * `ChatViewModel.setProvider` is the only consumer that can move state, and a
 * policy object is what made this rule exact rather than "guarded".
 */
class ProviderSwitchPolicyTest {

    @Test
    fun `asking for the provider already active is a no-op`() {
        // The settings flow re-emits on every preference change; a theme tweak
        // must not rebuild the workspace.
        assertEquals(
            ProviderSwitchPolicy.Decision.NoOp,
            ProviderSwitchPolicy.resolve(
                current = "opencode-zen",
                requested = "opencode-zen",
                runInFlight = false,
                pending = null
            )
        )
    }

    @Test
    fun `reverting to the already-active provider cancels a deferred switch`() {
        // Switch deferred during a run, then the user changed their mind. The
        // deferred switch must not fire at run end and surprise-rebuild the
        // workspace for a provider they no longer chose.
        assertEquals(
            ProviderSwitchPolicy.Decision.ClearPending,
            ProviderSwitchPolicy.resolve(
                current = "opencode-zen",
                requested = "opencode-zen",
                runInFlight = true,
                pending = "openrouter"
            )
        )
    }

    @Test
    fun `a switch with no turn in flight applies immediately`() {
        assertEquals(
            ProviderSwitchPolicy.Decision.ApplyNow,
            ProviderSwitchPolicy.resolve(
                current = "opencode-zen",
                requested = "openrouter",
                runInFlight = false,
                pending = null
            )
        )
    }

    @Test
    fun `a switch while a turn is in flight is deferred, not applied`() {
        // This is the ordering the bug got backwards: the answer died when the
        // handshake was rebuilt underneath it.
        assertEquals(
            ProviderSwitchPolicy.Decision.Defer,
            ProviderSwitchPolicy.resolve(
                current = "opencode-zen",
                requested = "openrouter",
                runInFlight = true,
                pending = null
            )
        )
    }

    @Test
    fun `a second switch during a run replaces the deferred one`() {
        // Only the final choice can be applied; keeping a queue would rebuild
        // the workspace twice for decisions the user no longer holds.
        assertEquals(
            ProviderSwitchPolicy.Decision.Defer,
            ProviderSwitchPolicy.resolve(
                current = "opencode-zen",
                requested = "hyper",
                runInFlight = true,
                pending = "openrouter"
            )
        )
    }

    @Test
    fun `the deferred switch applies once the run has ended`() {
        // The apply-at-run-end path resolves through the same rule, with the
        // deferred id as the request and the run no longer in flight.
        assertEquals(
            ProviderSwitchPolicy.Decision.ApplyNow,
            ProviderSwitchPolicy.resolve(
                current = "opencode-zen",
                requested = "openrouter",
                runInFlight = false,
                pending = "openrouter"
            )
        )
    }

    @Test
    fun `same provider with nothing pending stays a no-op even during a run`() {
        // A no-op must not manufacture work - or clear nothing into something.
        assertEquals(
            ProviderSwitchPolicy.Decision.NoOp,
            ProviderSwitchPolicy.resolve(
                current = "opencode-zen",
                requested = "opencode-zen",
                runInFlight = true,
                pending = null
            )
        )
    }
}
