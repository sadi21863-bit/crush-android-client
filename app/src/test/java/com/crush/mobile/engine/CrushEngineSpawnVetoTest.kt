package com.crush.mobile.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The spawn-veto seam of the atomic-birth interlock (design memo §1,
 * 2026-11-05).
 *
 * WHAT IS AND IS NOT UNIT-COVERED - read before assuming more:
 *
 * - Covered here: the pure birth-gate decision [spawnVetoed] (`maySpawn`
 *   consulted at the last moment, fail-closed on a throwing checkbox, exactly
 *   one evaluation per decision) and the veto's return-shape contract that the
 *   crash path mirrors (veto -> EngineState.Stopped, the state the supervise
 *   loop exits on by itself).
 *
 * - NOT unit-covered, honestly: (a) the DEFAULT checkbox value (`true`) - a
 *   "no behaviour change until the supervisor installs its own veto" tripwire
 *   - because reading it requires a CrushEngine instance, and building one
 *   needs an Android Context (EnginePaths resolves filesDir /
 *   nativeLibraryDir; the engine-layer tests are deliberately Robolectric-free,
 *   see EngineSocketsHostnameTest). The declaration keeps the default inline
 *   next to its comment so it cannot drift, and the device drive
 *   (TwoEngineInstancesPerProjectTest) covers the behaviour.
 *   (b) A `FakeMaySpawn = { false }` end-to-end start() returning
 *   EngineState.Stopped WITHOUT an `exec:`/spawn log line: same Context +
 *   real binary + real child process problem; ProcessBuilder cannot be faked
 *   on a plain JVM (java.lang.Process is not injectable). That shape is
 *   asserted on the device instead: a vetoed start must never produce an
 *   `exec:` line nor a `process` assignment, and the synchronized(this)
 *   birth-or-never critical section (MAY-SPAWN check -> ProcessBuilder.start()
 *   -> assignment, one step with stopBlocking's snapshot) is checked there too.
 */
class CrushEngineSpawnVetoTest {

    @Test
    fun `the veto is a plain negation of the checkbox`() {
        assertTrue("maySpawn=false must veto the birth", spawnVetoed({ false }))
        assertFalse("maySpawn=true must allow the birth", spawnVetoed({ true }))
    }

    @Test
    fun `a throwing checkbox fails closed to a veto`() {
        val hostile: () -> Boolean = {
            throw IllegalStateException("checkbox source misbehaved")
        }
        assertTrue(
            "a veto evaluation that throws must never let a child be born",
            spawnVetoed(hostile)
        )
    }

    @Test
    fun `the gate evaluates the checkbox lazily and exactly once`() {
        // The decision runs INSIDE the birth critical section: one evaluation
        // at the last moment, not a pre-cached or repeatedly-polled verdict
        // (a stale verdict is exactly the F06 race the interlock closes).
        var evals = 0
        val counting: () -> Boolean = { evals++; false }
        assertTrue(spawnVetoed(counting))
        assertEquals(1, evals)
    }

    @Test
    fun `a veto verdict is never cached - every birth section re-consults`() {
        // The birth gate runs the checkbox fresh on EVERY start attempt: a
        // stale "wanted" verdict from a previous attempt is exactly the F06
        // race the interlock closes (stop happened after an earlier check).
        var calls = 0
        val ticking: () -> Boolean = { calls++; calls == 1 }
        assertFalse("first pass: wanted, no veto", spawnVetoed(ticking))
        assertTrue("second pass: want revoked, veto", spawnVetoed(ticking))
    }
}
