package com.crush.mobile.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * V2-15 L2: a throwing child stop must not skip proxy cleanup, and a proxy
 * failure must not undo the child stop. Deterministic plain-JVM tests for the
 * same sequence `CrushEngine.stopBlocking` runs.
 */
class EngineStopCleanupTest {

    @Test
    fun `proxy still stops when child teardown throws`() {
        val calls = mutableListOf<String>()
        val failures = mutableListOf<String>()

        val done = runEngineStop(
            child = {
                calls += "child"
                throw IllegalStateException("destroy failed")
            },
            proxy = { calls += "proxy" },
            onFailure = { what, _ -> failures += what },
        )

        assertEquals(1, done)
        assertEquals(listOf("child", "proxy"), calls)
        assertEquals(listOf("child"), failures)
    }

    @Test
    fun `child was stopped before a proxy failure is reported`() {
        val calls = mutableListOf<String>()
        val failures = mutableListOf<String>()

        val done = runEngineStop(
            child = { calls += "child" },
            proxy = {
                calls += "proxy"
                throw IllegalStateException("bridge close failed")
            },
            onFailure = { what, _ -> failures += what },
        )

        assertEquals(1, done)
        assertEquals(listOf("child", "proxy"), calls)
        assertEquals(listOf("proxy"), failures)
    }

    @Test
    fun `no child still stops the proxy`() {
        var proxyCalls = 0
        val failures = mutableListOf<String>()

        val done = runEngineStop(
            child = null,
            proxy = { proxyCalls++ },
            onFailure = { what, _ -> failures += what },
        )

        assertEquals(1, done)
        assertEquals(1, proxyCalls)
        assertEquals(emptyList<String>(), failures)
    }
}
