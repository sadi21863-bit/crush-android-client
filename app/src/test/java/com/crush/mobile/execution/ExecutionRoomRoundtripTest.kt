package com.crush.mobile.execution

import android.content.Context
import com.crush.mobile.data.project.RoomProjectStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * V2-16 A2 slice 1: the additive v3->v4 migration and the guarded store.
 *
 * Same discipline as HandoffRoomRoundtripTest: own database file, hand-built
 * v3 DDL, real migration chain, field-by-field preservation, then a restart
 * read through a NEW store. No engine, no network, no prompt text.
 */
@RunWith(RobolectricTestRunner::class)
class ExecutionRoomRoundtripTest {

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private val dbName = "executions-roundtrip-test.db"

    @Before
    fun wipeTestDatabase() {
        context.deleteDatabase(dbName)
        context.getDatabasePath(dbName).parentFile?.let { dir ->
            listOf("-wal", "-shm", "-journal").forEach { suffix ->
                File(dir, "$dbName$suffix").delete()
            }
        }
    }

    /** Exact v3 shape: v1 projects + S-02 trust + P6 handoffs. */
    private fun createV3Database(label: String, path: String, id: UUID) {
        val db = context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `projects` (`id` TEXT NOT NULL, `label` TEXT NOT NULL, " +
                "`repo_dir` TEXT NOT NULL, `environment_level` TEXT NOT NULL, " +
                "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )
        db.execSQL("ALTER TABLE projects ADD COLUMN trust_fingerprint TEXT")
        db.execSQL("ALTER TABLE projects ADD COLUMN consented_at_ms INTEGER")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS handoffs (" +
                "handoffId TEXT NOT NULL PRIMARY KEY, " +
                "sourceChatId TEXT NOT NULL, " +
                "targetProjectId TEXT NOT NULL, " +
                "targetCodeSessionId TEXT, " +
                "summaryMarkdown TEXT NOT NULL, " +
                "userApprovedAt INTEGER, " +
                "selectedReferences TEXT NOT NULL, " +
                "projectTreeFingerprintAtHandoff TEXT, " +
                "expiryPolicy TEXT NOT NULL, " +
                "sourceProvider TEXT NOT NULL, " +
                "status TEXT NOT NULL CHECK (status IN " +
                "('DRAFT','APPROVED','DISPATCHED','ABANDONED')))"
        )
        db.execSQL(
            "INSERT INTO projects (id, label, repo_dir, environment_level, created_at, " +
                "updated_at) VALUES (?, ?, ?, ?, ?, ?)",
            arrayOf<Any>(id.toString(), label, path, "L0_EMBEDDED", 111L, 222L)
        )
        // G1: real handoff rows must exist BEFORE the v3→v4 migration so the
        // test can prove they survive it, not just that the table exists.
        db.execSQL(
            "INSERT INTO handoffs (handoffId, sourceChatId, targetProjectId, " +
                "targetCodeSessionId, summaryMarkdown, userApprovedAt, selectedReferences, " +
                "projectTreeFingerprintAtHandoff, expiryPolicy, sourceProvider, status) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any>(
                "h-full", "chat-1", id.toString(), "sess-9", "summary", 333L,
                "[]", "digest-1", "none", "openrouter", "DISPATCHED",
            )
        )
        db.execSQL(
            "INSERT INTO handoffs (handoffId, sourceChatId, targetProjectId, " +
                "targetCodeSessionId, summaryMarkdown, userApprovedAt, selectedReferences, " +
                "projectTreeFingerprintAtHandoff, expiryPolicy, sourceProvider, status) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(
                "h-null", "chat-2", id.toString(), null, "draft", null,
                "", null, "none", "openrouter", "DRAFT",
            )
        )
        db.version = 3
        db.close()
    }

    private fun row(id: String = "exec-1", phase: String = ExecutionLifecycle.INTENT_RECORDED.name) =
        ExecutionEntity(
            executionId = id,
            runId = "run-1",
            sessionId = "sess-1",
            engineKeyToken = testEngineKeyToken,
            workspacePath = "/storage/emulated/0/Test project folder 2",
            engineGeneration = 4L,
            leaseAtMs = 1_700_000_000_000L,
            phase = phase,
            progressAtMs = 1_700_000_000_000L,
            createdAtMs = 1_700_000_000_000L,
            updatedAtMs = 1_700_000_000_000L,
            ackState = ExecutionAckState.INTENT_ONLY.name,
        )

    private val testEngineKeyToken = "proj:11111111-1111-1111-1111-111111111111"

    @Test(timeout = 120_000)
    fun `migration chain preserves project and handoff rows and creates executions`() =
        runBlocking {
            val id = UUID.randomUUID()
            val path = File(context.filesDir, "exec-migrated/repo").absolutePath
            File(context.filesDir, "exec-migrated/repo").mkdirs()
            createV3Database("Migrated project", path, id)

            // Opening at the current version through the REAL registered chain
            // runs 3→4 and 4→5. With exportSchema=false this open IS the
            // schema validation.
            val store = RoomProjectStore(context, dbName)
            val preserved = store.byId(id)
            assertEquals("Migrated project", preserved?.label)
            assertEquals(path, preserved?.repoDir?.absolutePath)
            assertEquals("L0_EMBEDDED", preserved?.environmentLevel?.name)
            assertNull(store.trustRecordOf(id)?.fingerprint)

            val full = store.handoffDao().getById("h-full")!!
            assertEquals("chat-1", full.sourceChatId)
            assertEquals(id.toString(), full.targetProjectId)
            assertEquals("sess-9", full.targetCodeSessionId)
            assertEquals("summary", full.summaryMarkdown)
            assertEquals(333L, full.userApprovedAt)
            assertEquals("[]", full.selectedReferences)
            assertEquals("digest-1", full.projectTreeFingerprintAtHandoff)
            assertEquals("none", full.expiryPolicy)
            assertEquals("openrouter", full.sourceProvider)
            assertEquals("DISPATCHED", full.status)

            val draft = store.handoffDao().getById("h-null")!!
            assertEquals("draft", draft.summaryMarkdown)
            assertNull(draft.targetCodeSessionId)
            assertNull(draft.userApprovedAt)
            assertNull(draft.projectTreeFingerprintAtHandoff)
            assertEquals("DRAFT", draft.status)

            val raw = context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null)
            raw.rawQuery("SELECT sql FROM sqlite_master WHERE name = 'executions'", null).use { c ->
                assertTrue("executions table must exist after migration", c.moveToFirst())
                val ddl = c.getString(0)
                assertTrue(ddl.contains("execution_id"))
                assertTrue(ddl.contains("ack_state"))
                assertTrue("canonical lease column missing", ddl.contains("lease_epoch_ms"))
            }
            raw.rawQuery("SELECT created_at, updated_at FROM projects WHERE id = ?",
                arrayOf(id.toString())).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(111L, c.getLong(0))
                assertEquals(222L, c.getLong(1))
            }
            val indexes = raw.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'executions'",
                null,
            )
            val names = mutableListOf<String>()
            indexes.use { c -> while (c.moveToNext()) names += c.getString(0) }
            assertTrue(names.contains("idx_exec_session"))
            assertTrue(names.contains("idx_exec_engine"))
            assertTrue(names.contains("idx_exec_path"))
            raw.close()
        }

    @Test(timeout = 120_000)
    fun `an execution row roundtrips a restart byte-faithfully`() = runBlocking {
        val first = RoomProjectStore(context, dbName)
        val dao = first.executionDao()
        val original = row()
        dao.insert(original)

        val second = RoomProjectStore(context, dbName)
        val loaded = second.executionDao().byId("exec-1")!!
        assertEquals(original, loaded)
        assertEquals(ExecutionAckState.INTENT_ONLY.name, loaded.ackState)
        assertEquals(4L, loaded.engineGeneration)
        assertNull(loaded.promptRef)
    }

    @Test(timeout = 120_000)
    fun `a confirmed run advances only an acknowledged row`() = runBlocking {
        val dao = RoomProjectStore(context, dbName).executionDao()
        dao.insert(row("b4", ExecutionLifecycle.SUBMISSION_ACKNOWLEDGED.name).copy(
            ackState = ExecutionAckState.ACKED.name,
        ))

        assertEquals(1, dao.markRunningByRunId("run-1", 20_000L))
        assertEquals(ExecutionLifecycle.RUNNING.name, dao.byId("b4")!!.phase)
        // The second confirmed frame is a harmless no-op.
        assertEquals(0, dao.markRunningByRunId("run-1", 21_000L))

        dao.insert(
            row("no-ack", ExecutionLifecycle.SUBMISSION_ACKNOWLEDGED.name).copy(
                runId = "run-no-ack",
                ackState = ExecutionAckState.SENT_UNCONFIRMED.name,
            )
        )
        assertEquals(0, dao.markRunningByRunId("run-no-ack", 22_000L))
        assertEquals(
            ExecutionLifecycle.SUBMISSION_ACKNOWLEDGED.name,
            dao.byId("no-ack")!!.phase,
        )
    }

    @Test(timeout = 120_000)
    fun `stale phase writes are guarded no-ops`() = runBlocking {
        val store = RoomProjectStore(context, dbName)
        val dao = store.executionDao()
        dao.insert(row())

        // A row that never sent cannot be acknowledged.
        assertEquals(0, dao.markAcked("exec-1", 10_000L))
        assertEquals(ExecutionLifecycle.INTENT_RECORDED.name, dao.byId("exec-1")!!.phase)

        assertEquals(1, dao.markSent("exec-1", 11_000L))
        assertEquals(1, dao.markAcked("exec-1", 12_000L))
        // The same ack twice is a no-op; the second write cannot regress state.
        assertEquals(0, dao.markAcked("exec-1", 13_000L))
        assertEquals(ExecutionLifecycle.SUBMISSION_ACKNOWLEDGED.name, dao.byId("exec-1")!!.phase)

        // A terminal row is no longer recoverable.
        assertEquals(1, dao.markLost("exec-1", "reconcile:no_atomic_attach", 14_000L))
        assertEquals(ExecutionLifecycle.LOST.name, dao.byId("exec-1")!!.phase)
        assertEquals(0, dao.markRunning("exec-1", 15_000L))
        assertTrue(store.executionDao().recoverableRows().isEmpty())
    }

    @Test(timeout = 120_000)
    fun `stale writes cannot clobber ambiguity or an unknown outcome`() = runBlocking {
        val dao = RoomProjectStore(context, dbName).executionDao()
        dao.insert(
            row("amb", ExecutionLifecycle.SEND_ATTEMPTED.name).copy(
                ackState = ExecutionAckState.AMBIGUOUS.name,
            )
        )
        dao.insert(
            row("unk", ExecutionLifecycle.UNKNOWN.name).copy(
                terminalReason = "recovery:evidence_unavailable",
            )
        )

        assertEquals(0, dao.markRejected("amb", "late rejection", 30_000L))
        assertEquals(0, dao.markUnsent("amb", "late unsent", 30_000L))
        assertEquals(0, dao.markLost("unk", "late loss", 30_000L))
        assertEquals(ExecutionAckState.AMBIGUOUS.name, dao.byId("amb")!!.ackState)
        assertEquals(
            "recovery:evidence_unavailable",
            dao.byId("unk")!!.terminalReason,
        )
    }

    @Test(timeout = 120_000)
    fun `v4 to v5 copies the lease value into the canonical column`() = runBlocking {
        val id = UUID.randomUUID()
        createV3Database("v4 source", File(context.filesDir, "v4-source").absolutePath, id)

        val raw = context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null)
        // Same additive DDL as MIGRATION_3_4, applied directly so the fixture
        // is a real v4 on-disk schema with a lease_at_ms value to copy.
        raw.execSQL(
            "CREATE TABLE IF NOT EXISTS executions (execution_id TEXT NOT NULL PRIMARY KEY, " +
                "run_id TEXT NOT NULL, session_id TEXT NOT NULL, engine_key_token TEXT NOT NULL, " +
                "workspace_path TEXT NOT NULL, engine_generation INTEGER, lease_at_ms INTEGER, " +
                "phase TEXT NOT NULL, progress_at_ms INTEGER, created_at_ms INTEGER NOT NULL, " +
                "updated_at_ms INTEGER NOT NULL, prompt_ref TEXT, terminal_reason TEXT, " +
                "ack_state TEXT NOT NULL)"
        )
        raw.execSQL("CREATE INDEX IF NOT EXISTS idx_exec_session ON executions(session_id, updated_at_ms)")
        raw.execSQL("CREATE INDEX IF NOT EXISTS idx_exec_engine ON executions(engine_key_token, phase)")
        raw.execSQL("CREATE INDEX IF NOT EXISTS idx_exec_path ON executions(workspace_path)")
        raw.version = 4
        raw.execSQL(
            "INSERT INTO executions (execution_id, run_id, session_id, engine_key_token, " +
                "workspace_path, engine_generation, lease_at_ms, phase, created_at_ms, " +
                "updated_at_ms, ack_state) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any>(
                "v4row", "run-v4", "sess-1", "chat", "/workspace", 3L,
                1_700_000_000L, "RUNNING", 1L, 2L, "ACKED",
            )
        )
        raw.close()

        val store = RoomProjectStore(context, dbName)
        val loaded = store.executionDao().byId("v4row")!!
        assertEquals(1_700_000_000L, loaded.leaseEpochMs)
        assertEquals(3L, loaded.engineGeneration)

        val second = RoomProjectStore(context, dbName)
        assertEquals(loaded, second.executionDao().byId("v4row"))
    }
}
