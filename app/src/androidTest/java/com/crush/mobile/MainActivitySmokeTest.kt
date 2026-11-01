package com.crush.mobile

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented smoke suite (2026-10-31, rebuilt 2026-11-01): the app-level
 * "it installs, it composes, it survives" gate the external review asked for
 * as `androidTest`.
 *
 * Scope is deliberately launch-level, not conversational: engine turns have
 * live z-session device evidence and need a configured provider, while these
 * tests must pass on ANY install state (fresh install, locked, no key).
 *
 * REBUILT away from the Compose test rule: `createAndroidComposeRule` +
 * `waitForIdle()` can wait indefinitely when the launch lands behind the
 * app-lock gate (system dialogs keep the UI micro-idle-busy), and the first
 * device run proved exactly that wedge. `ActivityScenario` has no idle
 * dependency - the assertions are honest and bounded.
 */
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {

    @Test
    fun mainActivityComposesWorkableTree() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
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
    }

    @Test
    fun activityReachesStartedAndOwnsAWindow() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // Lifecycle truth, bounded by the environment (2026-11-01
                // device measurement): on an UNLOCKED phone the launch
                // reaches RESUMED; behind the DEVICE keyguard it honestly
                // parks at STARTED. Both prove the activity is live and owns
                // a window - the minimal contract every gated screen depends
                // on, whatever lock/provider state the device is in.
                assertTrue(
                    "expected STARTED, saw ${activity.lifecycle.currentState.name}",
                    activity.lifecycle.currentState.isAtLeast(
                        androidx.lifecycle.Lifecycle.State.STARTED
                    )
                )
                assertTrue(
                    "decor view must be attached",
                    activity.window.decorView.isAttachedToWindow
                )
            }
        }
    }
}
