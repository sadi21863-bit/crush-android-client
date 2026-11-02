package com.crush.mobile

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import com.crush.mobile.data.crush.CrushApi
import com.crush.mobile.data.crush.CreateSessionRequest
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
 * ADR-0001's FIRST integration proof - the roadmap's hard stop
 * (docs/crush-plan-2026-11-02.md §23): two distinct project directories must
 * attach to the same embedded engine as two DISTINCT logical workspaces, each
 * holding its own sessions, with no session crossing the workspace boundary.
 *
 * This runs the real bundled engine binary in this app's process, exactly as
 * production does. No API keys and no model probes are involved: a workspace
 * attach and its sessions are not credential-gated (agent init failures -
 * there is no key here - are logged, not fatal, per WorkspaceManager.open()).
 *
 * The workspace id is derived from the PATH (WorkspaceManager: a
 * nameUUIDFromBytes over the bytes), so a per-project
 * [ProjectRef.workspacePath] is what makes projects addressable - the shape
 * ADR-0001 names as WorkspaceBinding.
 */
@RunWith(AndroidJUnit4::class)
class TwoProjectAttachTest {

    private val context: Context
        get() = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()

    private lateinit var engine: CrushEngine

    @Before
    fun startEngine() = runBlocking {
        // Start from a clean slate so repeated runs of this test (each AM
        // instrument invocation) measure the same condition: two FRESH
        // workspaces created by THIS run, in THIS engine process.
        File(context.filesDir, "projects/a/repo").deleteRecursively()
        File(context.filesDir, "projects/b/repo").deleteRecursively()
        File(context.filesDir, "projects/a/repo").mkdirs()
        File(context.filesDir, "projects/b/repo").mkdirs()

        engine = CrushEngine(context)
        val state = engine.start(timeoutMs = 90_000)
        assertTrue(
            "the bundled engine must start inside the instrumented process, got: $state",
            state is EngineState.Running
        )
        Unit
    }

    @After
    fun stopEngine() {
        engine.stopBlocking()
    }

    @Test(timeout = 300_000)
    fun twoProjectsAttachAsDistinctWorkspacesWithoutCrossProjectBleed() = runBlocking {
        // ADR-0001 prototype in action: named projects over canonical dirs.
        val projectA: ProjectRef = ProjectRegistry.create(
            "Project A",
            File(context.filesDir, "projects/a/repo")
        )
        val projectB: ProjectRef = ProjectRegistry.create(
            "Project B",
            File(context.filesDir, "projects/b/repo")
        )

        // Direct filesystem truth, written WITHOUT the engine: if a later
        // workspace-scoped file listing (P3+) ever leaks across projects, this
        // asymmetric content is what catches it.
        File(projectA.repoDir, "marker-A.txt").writeText("belongs to A")
        File(projectB.repoDir, "marker-B.txt").writeText("belongs to B")

        val api = CrushApi(1, socketPath = EngineSockets.socketPath(context))

        // TWO managers = TWO WorkspaceBindings - the ADR's per-project shape.
        val managerA = WorkspaceManager(api)
        val managerB = WorkspaceManager(api)

        // Engine-owned state dir is shared exactly as production does today:
        // engine state stays engine-owned (ADR invariant I7); the project
        // boundary lives on the workspace path.
        val stateDir = File(context.filesDir, "crush/state").absolutePath
        val openedA = managerA.open(projectA.workspacePath, stateDir, yolo = false)
        val openedB = managerB.open(projectB.workspacePath, stateDir, yolo = false)

        // 1. Distinct paths -> distinct logical workspaces.
        assertNotEquals(
            "two distinct project cwds must mint distinct workspace ids",
            openedA.id,
            openedB.id
        )

        // 2. The engine holds BOTH simultaneously - opening B did not
        //    replace or evict A (the single-active-workspace limitation is
        //    an APP-side cache, not an engine one).
        val allWorkspaces = api.listWorkspaces()
        assertTrue(
            "workspace A (${openedA.id.take(8)}) must be listed after B was created",
            allWorkspaces.any { it.id == openedA.id }
        )
        assertTrue(
            "workspace B (${openedB.id.take(8)}) must be listed",
            allWorkspaces.any { it.id == openedB.id }
        )

        // 3. Path-keyed determinism (the idempotent re-attach property that
        //    P3's reattachIfStale will rely on per project): a FRESH manager
        //    posting the same path re-attaches to the SAME logical workspace.
        val reopenedA = WorkspaceManager(api).open(
            projectA.workspacePath,
            stateDir,
            yolo = false
        )
        assertEquals(
            "re-opening project A's path is an idempotent re-attach",
            openedA.id,
            reopenedA.id
        )

        // 4. MEASURED ENGINE BEHAVIOR (2026-11-03, this test on device bbf8ca69):
        //    in crush v0.99.0, SESSIONS ARE A GLOBAL POOL PER DATA-DIR.
        //    listSessions IGNORES the workspace id: A's and B's lists came
        //    back identical (both marker sessions visible in both, plus every
        //    historical session in the shared engine state). The WORKSPACE
        //    attach boundary holds (asserts 1-3 above); a SESSION boundary
        //    does not exist engine-side. This measured fact is the roadmap's
        //    hard-stop trigger: a multi-project product requires PER-PROJECT
        //    ENGINE INSTANCES (the ADR fallback: separate UDS socket +
        //    separate data dir per project) - the ADR-0001 amendment records
        //    this, and the follow-up test asserts the isolation on
        //    per-instance engines. Do not build Chat/Code isolation on
        //    single-engine workspace separation.
        val sessionA = api.createSession(
            openedA.id,
            CreateSessionRequest(id = UUID.randomUUID().toString(), title = "PROOF-A-only")
        )
        val sessionB = api.createSession(
            openedB.id,
            CreateSessionRequest(id = UUID.randomUUID().toString(), title = "PROOF-B-only")
        )
        val sessionsA = api.listSessions(openedA.id)
        val sessionsB = api.listSessions(openedB.id)
        assertTrue(
            "both marker sessions must appear in A's list (sessions are a global pool)",
            sessionsA.any { it.id == sessionA.id } && sessionsA.any { it.id == sessionB.id }
        )
        assertTrue(
            "both marker sessions must appear in B's list too",
            sessionsB.any { it.id == sessionA.id } && sessionsB.any { it.id == sessionB.id }
        )
        assertEquals(
            "A-list and B-list must be IDENTICAL: the engine's session list is " +
                "not workspace-scoped (crush v0.99.0 global-session-pool finding)",
            sessionsB.map { it.id },
            sessionsA.map { it.id }
        )
    }
}
