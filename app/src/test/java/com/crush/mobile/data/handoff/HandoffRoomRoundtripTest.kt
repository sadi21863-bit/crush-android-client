package com.crush.mobile.data.handoff

import android.content.Context
import com.crush.mobile.data.project.RoomProjectStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
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
 * P6 Batch A persistence proofs (2026-11-08), JVM-Robolectric tier - the
 * same suite choice and discipline as the S-02 `ProjectTrustRoomRoundtripTest`
 * (the on-device survival proof lives in the androidTest
 * `ProjectStoreRoomPersistenceTest` style; per the build plan it merges with
 * the restart-resume device drive later). Own database file, never the
 * production projects.db.
 *
 * 1. MIGRATION 2->3 preserves every pre-existing project row (and its S-02
 *    trust columns) and creates the handoffs table with the status CHECK
 *    inside the REAL migrated DDL. The v2 database is built by hand with the
 *    exact DDL Room expects of the v2 entity schema, so the migration is
 *    exercised against reality, and the open itself is the schema proof -
 *    with exportSchema=false Room validates the migrated tables against the
 *    entities it expects.
 * 2. A handoff walks the FULL status machine (DRAFT -> APPROVED ->
 *    DISPATCHED) and every column roundtrips a restart byte-faithfully
 *    (proof row (b)'s storage shape - Room rows only, no engine, no cards).
 * 3. Every status transition is a guarded no-op against a state the row is
 *    not in - the anti-clobber rule: a stale write returns 0 and the row
 *    keeps its state exactly.
 */
@RunWith(RobolectricTestRunner::class)
class HandoffRoomRoundtripTest {

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    /** Test-owned database name - deliberately NOT the production file. */
    private val dbName = "handoffs-roundtrip-test.db"

    @Before
    fun wipeTestDatabase() {
        context.deleteDatabase(dbName)
        context.getDatabasePath(dbName).parentFile?.let { dir ->
            listOf("-wal", "-shm", "-journal").forEach { suffix ->
                File(dir, "$dbName$suffix").delete()
            }
        }
    }

    /**
     * The v1 `projects` table DDL, byte-exact with what Room generates for
     * the pre-S-02 [ProjectEntity] (copied from ProjectTrustRoomRoundtripTest
     * verbatim), then the S-02 trust columns ALTERed on - i.e. the v2 shape
     * a pre-P6 install carries on disk. Plus one real project row, so the
     * migration is exercised against a populated table, not an empty one.
     */
    private fun createV2Database(label: String, path: String, id: UUID) {
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
            "INSERT INTO projects (id, label, repo_dir, environment_level, created_at, " +
                "updated_at) VALUES (?, ?, ?, ?, ?, ?)",
            arrayOf<Any>(id.toString(), label, path, "L0_EMBEDDED", 111L, 222L)
        )
        // Room's onUpgrade is driven by the user-version stamp - set to 2,
        // exactly what a pre-P6 install has.
        db.version = 2
        db.close()
    }

    private fun draft(id: String, sourceChatId: String, summary: String) = HandoffEntity(
        handoffId = id,
        sourceChatId = sourceChatId,
        targetProjectId = UUID.randomUUID().toString(),
        summaryMarkdown = summary,
        selectedReferences = "",
        sourceProvider = "glm-5.3-flash",
    )

    @Test(timeout = 120_000)
    fun `migration 2 to 3 preserves the project row and creates the checked handoffs table`() =
        runBlocking {
            val id = UUID.randomUUID()
            val path = File(context.filesDir, "handoff-migrated/repo").absolutePath
            File(context.filesDir, "handoff-migrated/repo").mkdirs()
            createV2Database("Migrated project", path, id)

            // Open at v3 with the full migration chain wired - the
            // RoomProjectStore shape exactly. The open itself is the schema
            // proof: with exportSchema=false Room validates the migrated
            // tables against the entities it expects, so a mismatch would
            // throw here, not in a suspicious read later.
            val store = RoomProjectStore(context, dbName)
            val row = store.byId(id)
            assertEquals("Migrated project", row?.label)
            assertEquals(path, row?.repoDir?.absolutePath)
            assertNull("a pre-P6 row must still read as NEVER trusted", store.trustRecordOf(id))

            // ProjectTrustSource wiring was not touched by Batch A: the
            // migrated v2 shape still accepts a trust write.
            store.writeTrustRecord(id, "all-zero-hex-placeholder", 999L)
            assertEquals("all-zero-hex-placeholder", store.trustRecordOf(id)?.fingerprint)

            // The CHECK is in the REAL migrated DDL (sqlite_master), not a
            // test-side copy of the SQL literal. Room's own connection is
            // idle-committed between queries, so a second read-only
            // connection sees the file.
            val raw = context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null)
            raw.rawQuery("SELECT sql FROM sqlite_master WHERE name = 'handoffs'", null).use { cursor ->
                assertTrue("the migrated table must exist", cursor.moveToFirst())
                val ddl = cursor.getString(0)
                assertTrue(
                    "the status CHECK must carry exactly the four-state domain: $ddl",
                    ddl.contains("status IN ('DRAFT','APPROVED','DISPATCHED','ABANDONED')"),
                )
            }
            raw.close()
        }

    @Test(timeout = 120_000)
    fun `a handoff walks the status machine and roundtrips a restart byte-faithfully`() =
        runBlocking {
            val store = RoomProjectStore(context, dbName)
            val dao = store.handoffDao()
            val draft = draft(
                id = UUID.randomUUID().toString(),
                sourceChatId = "chat-1",
                summary = "Ship the ledger fix",
            ).copy(
                selectedReferences = "[{\"label\":\"snippet 1\",\"text\":\"fun a() = 1\"}]",
            )
            assertNull("untouched draft has no approval stamp", draft.userApprovedAt)
            assertNull(draft.targetCodeSessionId)
            assertNull(draft.projectTreeFingerprintAtHandoff)
            dao.insertDraft(draft)

            val approvedAt = 1_111L
            assertEquals("first approve MUST land", 1, dao.markApproved(draft.handoffId, approvedAt))
            var row = dao.getById(draft.handoffId) ?: error("row must exist")
            assertEquals("APPROVED", row.status)
            assertEquals(approvedAt, row.userApprovedAt)
            assertNull("targetCodeSessionId stays NULL until dispatch", row.targetCodeSessionId)
            assertNull("the fingerprint stays NULL until dispatch", row.projectTreeFingerprintAtHandoff)
            assertEquals(draft.summaryMarkdown, row.summaryMarkdown)
            assertEquals(draft.selectedReferences, row.selectedReferences)
            assertEquals(draft.sourceChatId, row.sourceChatId)
            assertEquals(draft.targetProjectId, row.targetProjectId)
            assertEquals("NONE", row.expiryPolicy)

            assertEquals("dispatch lands exactly once", 1, dao.markDispatched(
                draft.handoffId, "code-session-7", "fingerprint-lowercase-hex"
            ))
            row = dao.getById(draft.handoffId) ?: error("row must exist")
            assertEquals("DISPATCHED", row.status)
            assertEquals("code-session-7", row.targetCodeSessionId)
            assertEquals("fingerprint-lowercase-hex", row.projectTreeFingerprintAtHandoff)
            assertEquals("the approval stamp must survive dispatch untouched", approvedAt, row.userApprovedAt)

            // The trace view is live and newest-first (rowid insertion order).
            val latest = dao.observeLatest().first()
            assertEquals(listOf(draft.handoffId), latest.map { it.handoffId })

            // Simulated restart: a NEW store over the SAME database file -
            // exactly what the next process's DI would construct. Proof row
            // (b)'s storage half: the trace survives verbatim.
            val restarted = RoomProjectStore(context, dbName)
            val trace = restarted.handoffDao().getById(draft.handoffId) ?: error("row must exist")
            assertEquals("DISPATCHED", trace.status)
            assertEquals("code-session-7", trace.targetCodeSessionId)
            assertEquals("fingerprint-lowercase-hex", trace.projectTreeFingerprintAtHandoff)
            assertEquals(approvedAt, trace.userApprovedAt)
            assertEquals(draft.summaryMarkdown, trace.summaryMarkdown)
            assertEquals(draft.selectedReferences, trace.selectedReferences)
            assertEquals(draft.sourceChatId, trace.sourceChatId)
            assertEquals(draft.targetProjectId, trace.targetProjectId)
            assertEquals(draft.sourceProvider, trace.sourceProvider)
            assertEquals("NONE", trace.expiryPolicy)
        }

    @Test(timeout = 120_000)
    fun `guarded transitions are no-ops against a state the row is not in`() = runBlocking {
        val dao = RoomProjectStore(context, dbName).handoffDao()
        val draftA = draft(UUID.randomUUID().toString(), "chat-1", "One")
        val draftB = draft(UUID.randomUUID().toString(), "chat-1", "Two")
        val draftOther = draft(UUID.randomUUID().toString(), "chat-2", "Other chat")
        dao.insertDraft(draftA)
        dao.insertDraft(draftB)
        dao.insertDraft(draftOther)

        // The CURRENT-draft lookup is per source chat, newest first.
        assertEquals(draftB.handoffId, dao.latestDraftForSource("chat-1")?.handoffId)
        assertEquals(draftOther.handoffId, dao.latestDraftForSource("chat-2")?.handoffId)

        // A DRAFT row cannot be dispatched: the guarded UPDATE returns 0 and
        // the row stays in its NULL-until-stage shape (SQL guard, not the
        // CHECK, enforces the order).
        assertEquals(0, dao.markDispatched(draftA.handoffId, "never-session", "never-fp"))
        val untouched = dao.getById(draftA.handoffId) ?: error("row must exist")
        assertEquals("DRAFT", untouched.status)
        assertNull(untouched.targetCodeSessionId)
        assertNull(untouched.projectTreeFingerprintAtHandoff)
        assertNull(untouched.userApprovedAt)

        // The anti-clobber no-op: after DISPATCHED, a stale approval write
        // returns 0 and NOTHING moves (status, stamp and session all stay).
        assertEquals(1, dao.markApproved(draftB.handoffId, 55L))
        assertEquals(1, dao.markDispatched(draftB.handoffId, "session-9", "fp-9"))
        assertEquals(0, dao.markApproved(draftB.handoffId, 99L))
        val dispatched = dao.getById(draftB.handoffId) ?: error("row must exist")
        assertEquals("DISPATCHED", dispatched.status)
        assertEquals("the stale stamp must NOT have been written", 55L, dispatched.userApprovedAt)
        assertEquals("session-9", dispatched.targetCodeSessionId)

        // A dispatched row is out of the current-draft view entirely; the
        // lookup falls back to the still-open older draft.
        assertEquals(draftA.handoffId, dao.latestDraftForSource("chat-1")?.handoffId)

        // A DISPATCHED row can never be abandoned - it is the durable trace.
        assertEquals(0, dao.abandon(draftB.handoffId))
        assertEquals("DISPATCHED", dao.getById(draftB.handoffId)?.status)

        // Abandoning a still-APPROVED row is the no-alias teardown (plan
        // :416); after it the row leaves the current-draft view for good.
        assertEquals(1, dao.markApproved(draftA.handoffId, 77L))
        assertEquals(1, dao.abandon(draftA.handoffId))
        assertEquals("ABANDONED", dao.getById(draftA.handoffId)?.status)
        assertNull(dao.latestDraftForSource("chat-1"))
    }
}
