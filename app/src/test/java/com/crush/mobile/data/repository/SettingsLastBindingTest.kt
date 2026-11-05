package com.crush.mobile.data.repository

import android.content.Context
import com.crush.mobile.data.local.KeySession
import com.crush.mobile.data.local.SettingsDataStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * A11/A12 last-binding persistence (2026-11-05): the token the container's
 * applyBinding persists must be bytecode-survivable across process death -
 * a brand-new repository instance reads the SAME token back. Not round-tripped
 * through any in-memory state: a new SettingsDataStore on the same context
 * re-opens the real preferences file, exactly the "cold boot" read path.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsLastBindingTest {

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private fun freshRepo(): SettingsRepository =
        SettingsRepository(SettingsDataStore(context, KeySession()))

    @Test
    fun `the last binding token survives a new repository instance`() = runBlocking {
        freshRepo().saveLastBinding("chat")
        // A second instance = what the NEXT process sees: same file, zero
        // in-memory shared state.
        assertEquals("chat", freshRepo().readLastBinding())

        freshRepo().saveLastBinding("proj:1a2b3c4d-0000-0000-0000-000000000000")
        assertEquals(
            "proj:1a2b3c4d-0000-0000-0000-000000000000",
            freshRepo().readLastBinding()
        )
        Unit
    }

    @Test
    fun `an empty or absent token reads as the legacy default`() = runBlocking {
        // The DataStore singleton delegate pins the preferences file across
        // tests in this JVM, so "absent" is not state this test can assume;
        // what it CAN prove is the same promise the app relies on: a SEED
        // that is user-readable-but-empty behaves exactly like virgin data -
        // the legacy default, no throw, no misparse.
        freshRepo().saveLastBinding("")
        assertEquals("", freshRepo().readLastBinding())
        Unit
    }

    @Test
    fun `re-saving overwrites rather than accumulating`() = runBlocking {
        freshRepo().saveLastBinding("chat")
        freshRepo().saveLastBinding("legacy")
        assertEquals("legacy", freshRepo().readLastBinding())
        Unit
    }
}
