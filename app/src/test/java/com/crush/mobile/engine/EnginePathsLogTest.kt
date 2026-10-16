package com.crush.mobile.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The engine's log directory name embeds the ephemeral bind port
 * (server-tcp___127.0.0.1_<port>), so the reader must DISCOVER it, not guess.
 * The former fixed path never existed, and the fallback diagnostics reader
 * silently collected nothing.
 */
class EnginePathsLogTest {

    private lateinit var cache: File

    @Before
    fun setUp() {
        cache = Files.createTempDirectory("cache").toFile()
    }

    @After
    fun tearDown() {
        cache.deleteRecursively()
    }

    @Test
    fun `no server directory yields null`() {
        assertNull(EnginePaths.newestEngineLog(cache))
    }

    @Test
    fun `the log inside the real port-embedded directory is found`() {
        val dir = File(cache, "server-tcp___127.0.0.1_45671").apply { mkdirs() }
        val log = File(dir, "crush.log").apply { writeText("engine log") }
        assertEquals(log, EnginePaths.newestEngineLog(cache))
    }

    @Test
    fun `the newest log wins when several server directories exist`() {
        val old = File(cache, "server-tcp___127.0.0.1_1001/crush.log").apply {
            mkdirs(); writeText("old")
        }
        val new = File(cache, "server-tcp___127.0.0.1_2002/crush.log").apply {
            mkdirs(); writeText("new")
        }
        old.setLastModified(1_000)
        new.setLastModified(2_000)
        assertEquals(new, EnginePaths.newestEngineLog(cache))
    }

    @Test
    fun `a server directory without crush.log is not a candidate`() {
        File(cache, "server-tcp___127.0.0.1_3003").mkdirs()
        assertNull(EnginePaths.newestEngineLog(cache))
    }

    @Test
    fun `directories that do not look like server listeners are ignored`() {
        File(cache, "workspace/crush.log").apply { mkdirs(); writeText("not an engine log") }
        assertNull(EnginePaths.newestEngineLog(cache))
    }
}
