package com.crush.mobile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.crush.mobile.data.crush.CreateSessionRequest
import com.crush.mobile.data.crush.CrushApi
import com.crush.mobile.data.crush.WorkspaceManager
import com.crush.mobile.engine.CrushEngine
import com.crush.mobile.engine.EngineSockets
import com.crush.mobile.engine.EngineState
import com.crush.mobile.project.ProjectRef
import com.crush.mobile.project.ProjectRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ADR-0001 amendment's follow-up proof (2026-11-03): the MEASURED fallback
 * for the single-engine session-pool finding. The first proof
 * (`TwoProjectAttachTest`) showed workspace attach is path-keyed and
 * multi-capable while sessions are a GLOBAL pool per engine data dir. This
 * test asserts the isolation the product needs, on PER-PROJECT ENGINE
 * INSTANCES: two engine children ("pa"/"pb"), each with its OWN socket and
 * its OWN state dir, serving one project each.
 *
 * Each engine is booted the production way (real bundled binary, child
 * process, app-private transport). No keys are used: attach and session
 * calls are not credential-gated.
 */
@RunWith(AndroidJUnit4::class)
class TwoEngineInstancesPerProjectTest {

    private val context: Context
        get() = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()

    private lateinit var engineA: CrushEngine
    private lateinit var engineB: CrushEngine

    @Before
    fun startEngines() = runBlocking {
        // Deterministic runs: fresh per-instance state dirs AND fresh project
        // dirs, so yesterday's/user's data cannot participate.
        listOf(
            File(context.filesDir, "crush-pa"),
            File(context.filesDir, "crush-pb"),
            File(context.filesDir, "projects/a/repo"),
            File(context.filesDir, "projects/b/repo")
        ).forEach { it.deleteRecursively() }
        File(context.filesDir, "crush-pa").mkdirs()
        File(context.filesDir, "crush-pb").mkdirs()

        engineA = CrushEngine(context, instanceKey = "pa")
        engineB = CrushEngine(context, instanceKey = "pb")
        val stateA = engineA.start(timeoutMs = 90_000)
        val stateB = engineB.start(timeoutMs = 90_000)
        assertTrue(
            "engine instance A must start, got: $stateA",
            stateA is EngineState.Running
        )
        assertTrue(
            "engine instance B must start, got: $stateB",
            stateB is EngineState.Running
        )
        Unit
    }

    @After
    fun stopEngines() {
        engineA.stopBlocking()
        engineB.stopBlocking()
    }

    @Test(timeout = 300_000)
    fun perProjectEnginesIsolateSessionsAndWorkspaces() = runBlocking {
        val projectA: ProjectRef = ProjectRegistry.create(
            "Project A",
            File(context.filesDir, "projects/a/repo")
        )
        val projectB: ProjectRef = ProjectRegistry.create(
            "Project B",
            File(context.filesDir, "projects/b/repo")
        )
        File(projectA.repoDir, "marker-A.txt").writeText("belongs to A")
        File(projectB.repoDir, "marker-B.txt").writeText("belongs to B")

        // Each project's client reaches ONLY its engine instance's socket.
        val apiA = CrushApi(1, socketPath = EngineSockets.socketPath(context, "pa"))
        val apiB = CrushApi(1, socketPath = EngineSockets.socketPath(context, "pb"))

        val managerA = WorkspaceManager(apiA)
        val managerB = WorkspaceManager(apiB)
        val stateDirA = File(context.filesDir, "crush-pa/state").absolutePath
        val stateDirB = File(context.filesDir, "crush-pb/state").absolutePath
        val openedA = managerA.open(projectA.workspacePath, stateDirA, yolo = false)
        val openedB = managerB.open(projectB.workspacePath, stateDirB, yolo = false)

        // 1. Different engines, different id spaces (fresh state dirs -> both
        //    ids are the path-derived value, but assert distinct anyway).
        assertNotEquals(openedA.id, openedB.id)

        // 2. Engine-instance isolation at the Workspaces level: A's engine
        //    must NOT even know B's workspace and vice versa.
        val workspacesA = apiA.listWorkspaces()
        val workspacesB = apiB.listWorkspaces()
        assertTrue("A's engine must know A's workspace", workspacesA.any { it.id == openedA.id })
        assertTrue("B's engine must know B's workspace", workspacesB.any { it.id == openedB.id })
        assertFalse(
            "A's engine must NOT know B's workspace",
            workspacesA.any { it.id == openedB.id }
        )
        assertFalse(
            "B's engine must NOT know A's workspace",
            workspacesB.any { it.id == openedA.id }
        )

        // 3. Session-level isolation: THE thing the single-engine proof
        //    measured as missing. Marker sessions must list ONLY on their own
        //    engine instance, and neither instance may carry the other's
        //    session (or any historical session, since state dirs are fresh).
        val sessionA = apiA.createSession(
            openedA.id,
            CreateSessionRequest(id = UUID.randomUUID().toString(), title = "PROOF-A-only")
        )
        val sessionB = apiB.createSession(
            openedB.id,
            CreateSessionRequest(id = UUID.randomUUID().toString(), title = "PROOF-B-only")
        )
        val sessionsA = apiA.listSessions(openedA.id)
        val sessionsB = apiB.listSessions(openedB.id)
        assertEquals(
            "A's engine lists EXACTLY A's marker session",
            listOf(sessionA.id),
            sessionsA.map { it.id }
        )
        assertEquals(
            "B's engine lists EXACTLY B's marker session",
            listOf(sessionB.id),
            sessionsB.map { it.id }
        )
        assertFalse(sessionsA.any { it.id == sessionB.id })
        assertFalse(sessionsB.any { it.id == sessionA.id })
    }
}
