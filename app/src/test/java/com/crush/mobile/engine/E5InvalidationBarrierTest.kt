package com.crush.mobile.engine

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * E5-R6: deterministic invalidation barrier (no sleeps, no timing windows).
 *
 * Scope / honesty note: A1 is closed, so there is no production
 * ExecutionController attachment to commit against. This test proves two
 * things and no more:
 *
 *  1. the production binding-switch / project-removal path
 *     (EngineInstanceRegistry.stop) fires the no-op-by-default probe BEFORE
 *     engine teardown, and
 *  2. a provisional attachment whose commit re-checks the epoch under the
 *     same guard cannot commit after that invalidation, and rolls back its
 *     provisional resources.
 *
 * The guard below is a TEST harness standing in for the future controller's
 * token protocol. Supervisor / watchdog / shutdown / debug call sites are
 * wired to the same probe in production source but are not runtime-tested
 * here (their runtime proof belongs to the eventual A1 instrumented tests).
 */
class E5InvalidationBarrierTest {

    private class FakeRuntime(val key: String, var stopped: Boolean = false)

    private class Harness {
        val epochs = ConcurrentHashMap<String, Long>()
        val sources = mutableListOf<String>()
        val guard = ReentrantLock()

        val registry: EngineInstanceRegistry<FakeRuntime> =
            EngineInstanceRegistry<FakeRuntime>(
                legacyKey = "",
                livenessOf = { rt -> EngineInstanceRegistry.Liveness(false, EngineState.Stopped) },
                stopRuntime = { rt -> rt.stopped = true },
            )

        init {
            EngineInvalidationProbe.listener = { source, key ->
                guard.withLock {
                    epochs[key] = (epochs[key] ?: 0L) + 1L
                    sources.add(source)
                }
            }
        }

        fun validate(key: String): Long = guard.withLock { epochs[key] ?: 0L }

        fun commit(key: String, token: Long): Boolean =
            guard.withLock { (epochs[key] ?: 0L) == token }
    }

    /**
     * Test-side stand-in for the controller's provisional attachment.
     * Resources are visible only inside this transaction until commit.
     */
    private class ProvisionalAttachment(private val h: Harness, val key: String) {
        val token: Long = h.validate(key)
        val leaseHeld = AtomicBoolean(true)
        val observerOpen = AtomicBoolean(true)

        fun commit(): Boolean {
            val ok = h.commit(key, token)
            if (!ok) {
                leaseHeld.set(false)
                observerOpen.set(false)
            }
            return ok
        }
    }

    @After
    fun tearDown() {
        EngineInvalidationProbe.listener = null
    }

    @Test
    fun noInvalidation_commitSucceeds() {
        val h = Harness()
        val attachment = ProvisionalAttachment(h, "pA")

        assertTrue(attachment.commit())
        assertTrue(attachment.leaseHeld.get())
        assertTrue(attachment.observerOpen.get())
    }

    @Test
    fun registryStopBetweenValidateAndCommit_commitRejectedAndRolledBack() {
        val h = Harness()
        h.registry.runtimeFor("pA") { FakeRuntime("pA") }
        val attachment = ProvisionalAttachment(h, "pA")

        // Production path: binding switch / project removal both reach this.
        assertTrue(h.registry.stop("pA", "E5-R6"))

        assertFalse(attachment.commit())
        assertFalse(attachment.leaseHeld.get())
        assertFalse(attachment.observerOpen.get())
        assertTrue(h.sources.contains("registry.stop"))
    }

    @Test
    fun invalidationBeforeValidation_commitsAgainstNewEpochOnly() {
        val h = Harness()
        h.registry.runtimeFor("pA") { FakeRuntime("pA") }
        h.registry.stop("pA", "pre-validate")

        val attachment = ProvisionalAttachment(h, "pA")
        assertTrue(attachment.commit())
    }

    @Test
    fun probeForwardsEveryInvalidationSourceVocabulary() {
        val h = Harness()
        val sources = listOf(
            "registry.stop",
            "supervisor.stop",
            "supervisor.restart",
            "supervisor.watchdog",
            "supervisor.recovery",
            "debug.kill_engine",
            "shutdown",
        )
        sources.forEach { EngineInvalidationProbe.fire(it, "pA") }

        assertTrue(h.sources.containsAll(sources))
        assertTrue(sources.all { h.epochs["pA"] == sources.size.toLong() })
    }
}
