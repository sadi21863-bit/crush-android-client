package com.crush.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The launch route must be decided once per process (Finding 12).
 *
 * The defect these pin: a provider switch in Settings changes the selected
 * provider's key state, which used to change the NavHost start destination and
 * discard the user's back stack. The route is now a launch decision; nothing a
 * settings write does may re-decide it in-process.
 */
class LaunchRouteStateTest {

    @Test
    fun `the first decision is pinned even when the vault changes`() {
        val route = LaunchRouteState()
        var hasKey = true

        assertEquals(true, route.startWithChat { hasKey })

        // Same process, later frame: the user deleted the key or switched to a
        // provider without one. The launch route must NOT flip - that flip is
        // what rebuilt the graph and reset navigation.
        hasKey = false
        assertEquals(true, route.startWithChat { hasKey })
    }

    @Test
    fun `a cold start without a key pins onboarding and stays there`() {
        val route = LaunchRouteState()
        var hasKey = false

        assertEquals(false, route.startWithChat { hasKey })

        // A key saved during this process is handled by explicit navigation
        // (onboarding -> chat). It must not mutate the launch decision.
        hasKey = true
        assertEquals(false, route.startWithChat { hasKey })
    }

    @Test
    fun `the vault is consulted exactly once`() {
        val route = LaunchRouteState()
        var calls = 0

        repeat(5) {
            route.startWithChat {
                calls += 1
                true
            }
        }

        assertEquals(1, calls)
    }

    @Test
    fun `a failed probe does not pin a decision`() {
        // An unreadable vault must not silently route the user for the rest of
        // the process; the next call re-reads.
        val route = LaunchRouteState()

        runCatching { route.startWithChat { error("vault unreadable") } }

        assertEquals(false, route.startWithChat { false })
    }
}
