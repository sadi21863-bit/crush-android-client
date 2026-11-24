package com.crush.mobile.engine

import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * V2-15 L2: stopBlocking must be safe before any process exists, and repeated
 * stops must stay no-ops. The proxy-always-stops behavior is pinned by
 * [EngineProxyConnectTest]; this class covers the engine entry point without
 * spawning a child.
 */
@RunWith(RobolectricTestRunner::class)
class CrushEngineLifecycleTest {

    @Test
    fun `stop before start is safe and repeated stop is idempotent`() {
        val engine = CrushEngine(
            RuntimeEnvironment.getApplication(),
            "p-v2-15-lifecycle",
            idleTimeoutSec = 60,
        )

        assertFalse("a constructed engine has no child", engine.isAlive())
        engine.stopBlocking()
        engine.stopBlocking()
        assertFalse("stop must not invent a child", engine.isAlive())
    }
}
