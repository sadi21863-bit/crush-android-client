package com.crush.mobile.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * V2-3 shell policy, pinned without a device.
 *
 * Breakpoints are brief 4.6: under 600dp bottom bar, 600-839dp rail, 840dp
 * and up extended rail. Anything that is not a primary destination gets no
 * shell chrome at all.
 */
class PrimaryDestinationsTest {

    @Test
    fun `exactly three primary destinations and nothing else`() {
        assertEquals(3, PrimaryDestination.entries.size)
        assertEquals(PrimaryDestination.Chat, PrimaryDestination.fromRoute("chat"))
        assertEquals(PrimaryDestination.Projects, PrimaryDestination.fromRoute("projects"))
        assertEquals(PrimaryDestination.Settings, PrimaryDestination.fromRoute("settings"))
        assertNull(PrimaryDestination.fromRoute("onboarding"))
        assertNull(PrimaryDestination.fromRoute("review"))
        assertNull(PrimaryDestination.fromRoute("diagnostics"))
        assertNull(PrimaryDestination.fromRoute(null))
    }

    @Test
    fun `bottom bar below 600dp`() {
        assertEquals(ShellLayout.BottomBar, shellLayoutFor("chat", 0f))
        assertEquals(ShellLayout.BottomBar, shellLayoutFor("chat", 411f))
        assertEquals(ShellLayout.BottomBar, shellLayoutFor("chat", 599.9f))
    }

    @Test
    fun `rail from 600dp to 839dp`() {
        assertEquals(ShellLayout.Rail, shellLayoutFor("chat", 600f))
        assertEquals(ShellLayout.Rail, shellLayoutFor("projects", 800f))
        assertEquals(ShellLayout.Rail, shellLayoutFor("settings", 839.9f))
    }

    @Test
    fun `extended rail at 840dp and above`() {
        assertEquals(ShellLayout.ExtendedRail, shellLayoutFor("chat", 840f))
        assertEquals(ShellLayout.ExtendedRail, shellLayoutFor("projects", 1280f))
    }

    @Test
    fun `non-primary routes hide the shell at every width`() {
        listOf(null, "onboarding", "review", "diagnostics").forEach { route ->
            listOf(0f, 411f, 600f, 840f, 1280f).forEach { width ->
                assertEquals(
                    "route=$route width=$width",
                    ShellLayout.Hidden,
                    shellLayoutFor(route, width),
                )
            }
        }
    }

    @Test
    fun `the three destinations have distinct routes and labels`() {
        val routes = PrimaryDestination.entries.map { it.route }
        val labels = PrimaryDestination.entries.map { it.labelRes }
        assertEquals(routes.size, routes.distinct().size)
        assertEquals(labels.size, labels.distinct().size)
        assertEquals(listOf("chat", "projects", "settings"), routes)
    }
}
