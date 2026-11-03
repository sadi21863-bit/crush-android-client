package com.crush.mobile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.crush.mobile.data.project.RoomProjectStore
import com.crush.mobile.project.ProjectRef
import com.crush.mobile.project.ProjectStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P2 persistence proof (ADR-0001 §5): the Room-backed [ProjectStore] survives
 * a process restart. This is a STORAGE proof — no engine, no sockets; the
 * workspace/isolation behavior is proven separately in
 * `TwoEngineInstancesPerProjectTest`, and identity mapping is covered there
 * too. What must hold here:
 *
 * 1. Rows minted through one store instance reappear, byte-faithful (same
 *    ids, labels, workspace paths, environment level, same order), through a
 *    NEW store instance over the SAME database file - the exact shape a
 *    restarted process constructs.
 * 2. The legacy workspace migration is idempotent BY PATH: re-running
 *    `migrateLegacyWorkspace` re-reads the existing row instead of minting a
 *    twin (the ADR's additive-migration invariant).
 *
 * Runs on its own database file (`projects-test.db`) so it competes with
 * nothing: the app's real registry (when P3 wires it) lives in projects.db.
 */
@RunWith(AndroidJUnit4::class)
class ProjectStoreRoomPersistenceTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    /** Test-owned database name - deliberately NOT the production file. */
    private val dbName = "projects-test.db"

    @Before
    fun wipeTestDatabase() {
        context.deleteDatabase(dbName)
        // Sidecar files can outlive the main-file delete on some framework
        // versions; remove them explicitly so every run starts from zero.
        context.getDatabasePath(dbName).parentFile?.let { dir ->
            listOf("-wal", "-shm", "-journal").forEach { suffix ->
                File(dir, "$dbName$suffix").delete()
            }
        }
    }

    @Test(timeout = 120_000)
    fun projectsSurviveProcessRestart() = runBlocking {
        val storeA: ProjectStore = RoomProjectStore(context, dbName)
        val a = storeA.create("Persistence A", File(context.filesDir, "projects/room-a/repo"))
        val b = storeA.create("Persistence B", File(context.filesDir, "projects/room-b/repo"))

        // Simulated restart: a NEW store over the SAME database file, exactly
        // what the next process's DI would construct.
        val storeB: ProjectStore = RoomProjectStore(context, dbName)
        val restored = storeB.list()

        assertEquals(
            "restored ids must match the pre-restart ids, same order",
            listOf(a.id, b.id),
            restored.map { it.id }
        )
        assertEquals("labels survive verbatim", a.label, restored[0].label)
        assertEquals(
            "the workspace path (what the engine keys on) survives byte-exact",
            a.repoDir.absolutePath,
            restored[0].repoDir.absolutePath
        )
        assertEquals(
            ProjectRef.EnvironmentLevel.L0_EMBEDDED,
            restored[0].environmentLevel
        )
        assertEquals(
            "byId resolves a pre-restart identity",
            a.id,
            storeB.byId(a.id)?.id
        )
    }

    @Test(timeout = 120_000)
    fun legacyWorkspaceMigrationIsIdempotentByPath() = runBlocking {
        val legacy = File(context.filesDir, "workspace").apply { mkdirs() }
        val store = RoomProjectStore(context, dbName)

        val first = store.migrateLegacyWorkspace(legacy)
        assertEquals(
            "the legacy row's path is the legacy workspace itself",
            legacy.absolutePath,
            first.repoDir.absolutePath
        )
        val second = store.migrateLegacyWorkspace(legacy)
        assertEquals(
            "re-running the migration re-reads the legacy row, no twin row",
            first.id,
            second.id
        )
        assertEquals(
            "exactly one row claims the legacy path after migration + re-run",
            1,
            store.list().count { it.repoDir.absolutePath == legacy.absolutePath }
        )
    }
}
