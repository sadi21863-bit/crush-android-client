package com.crush.mobile

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented smoke suite (2026-10-31): the app-level "it installs, it
 * composes, it survives" gate the external review asked for as `androidTest`.
 *
 * Scope is deliberately launch-level, not conversational: engine turns have
 * live z-session device evidence and need a configured provider, while these
 * tests must pass on ANY install state (fresh install, locked, no key). A
 * "look fine but compose nothing" regression - the exact defect class a green
 * unit build has shipped before - fails loud here.
 */
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun mainActivityComposesWorkableTree() {
        // The compose host must actually have content attached: an empty
        // (zero-node) tree means composition never ran and the screen would
        // be blank while looking "successful".
        compose.waitForIdle()
        compose.activityRule.scenario.onActivity { activity ->
            val host = checkNotNull(
                activity.findViewById<android.view.ViewGroup>(
                    android.R.id.content
                )
            ) { "Activity content root missing" }
            check(host.childCount > 0) {
                "Compose tree never attached (content root has no children)"
            }
        }
    }

    @Test
    fun activityReachesResumedAndOwnsAWindow() {
        // Lifecycle truth: the launch actually got here (not insta-crashed
        // into system-dialog land) and the activity owns a window with the
        // decor view attached - the minimal contract every gated screen
        // depends on, whatever lock/provider state the device is in.
        compose.activityRule.scenario.onActivity { activity ->
            assertTrue(
                "expected RESUMED, saw ${activity.lifecycle.currentState.name}",
                activity.lifecycle.currentState.isAtLeast(
                    androidx.lifecycle.Lifecycle.State.RESUMED
                )
            )
            assertTrue(
                "decor view must be attached",
                activity.window.decorView.isAttachedToWindow
            )
        }
    }
}
