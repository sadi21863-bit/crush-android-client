package com.crush.mobile.data.project

import android.content.Context
import com.crush.mobile.project.ProjectRef
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
 * S-02 persistence proofs (2026-11-05), JVM-Robolectric tier (the same suite
 * choice the store data-layer tests need; the on-device Room proof lives in
 * the androidTest `ProjectStoreRoomPersistenceTest`, which this file nods to
 * in DbName discipline - own file, never the production projects.db):
 *
 * 1. MIGRATION 1->2 preserves every pre-existing row byte-faithfully and
 *    makes the trust columns available with a NULL state (never trusted).
 *    The v1 database is built by hand with the EXACT DDL Room expects of the
 *    v1 entity schema, so the migration is exercised against reality, not
 *    against a mock.
 * 2. A consent record roundtrips the "cold boot" shape: recorded through one
 *    store/service instance, read - and gate-Allow'ed - through a NEW
 *    instance over the SAME database file.
 * 3. Removing the row removes the consent with it (the S-02 removal
 *    contract: the row IS the trust storage).
 *
 * What is deliberately NOT here: engine/wiring behavior. The gate verdict
 * matrix is the pure-JVM ProjectTrustServiceTest; container-level wiring is
 * a device acceptance row (pack matrix A03).
 */
@RunWith(RobolectricTestRunner::class)
class ProjectTrustRoomRoundtripTest {

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    /** Test-owned database name - deliberately NOT the production file. */
    private val dbName = "projects-trust-test.db"

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
     * the pre-S-02 [ProjectEntity] (six columns, id PRIMARY KEY, all
     * NOT NULL). This is the schema a pre-S-02 install carries on disk.
     */
    private fun createV1Database(label: String, path: String, id: UUID) {
        val db = context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `projects` (`id` TEXT NOT NULL, `label` TEXT NOT NULL, " +
                "`repo_dir` TEXT NOT NULL, `environment_level` TEXT NOT NULL, " +
                "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "INSERT INTO projects (id, label, repo_dir, environment_level, created_at, " +
                "updated_at) VALUES (?, ?, ?, ?, ?, ?)",
            arrayOf<Any>(id.toString(), label, path, "L0_EMBEDDED", 111L, 222L)
        )
        // Room's onUpgrade is driven by the user-version stamp - set to 1,
        // exactly what a pre-S-02 install has.
        db.version = 1
        db.close()
    }

    @Test(timeout = 120_000)
    fun `migration 1 to 2 preserves the row and exposes an empty trust state`() = runBlocking {
        val id = UUID.randomUUID()
        val path = File(context.filesDir, "trust-migrated/repo").absolutePath
        File(context.filesDir, "trust-migrated/repo").mkdirs()
        createV1Database("Migrated project", path, id)

        // Open at v2 with the migration wired - the "upgrade installs must
        // not lose a single project row" run.
        val store = RoomProjectStore(context, dbName)
        val row = store.byId(id)
        assertEquals("Migrated project", row?.label)
        assertEquals(path, row?.repoDir?.absolutePath)
        assertEquals(ProjectRef.EnvironmentLevel.L0_EMBEDDED, row?.environmentLevel)
        assertNull("a pre-S-02 row must read as NEVER trusted", store.trustRecordOf(id))
        // ...and the migrated db accepts a trust write (columns were added).
        store.writeTrustRecord(id, "all-zero-hex-placeholder", 999L)
        assertEquals("all-zero-hex-placeholder", store.trustRecordOf(id)?.fingerprint)
    }

    @Test(timeout = 120_000)
    fun `a recorded consent roundtrips a restart and gate-allows`() = runBlocking {
        val dir = File(context.filesDir, "trust/repo").apply { mkdirs() }
        File(dir, "crushrc").writeText("date >> marker")

        val storeA = RoomProjectStore(context, dbName)
        val created = storeA.create("Trust A", dir)
        val serviceA = ProjectTrustService(storeA)
        assertTrue(
            "first sight must gate",
            serviceA.gate(created) is TrustVerdict.Required
        )
        serviceA.recordConsent(created)

        // Restart: a NEW store + service over the SAME database file -
        // exactly what the next process's DI would construct.
        val storeB = RoomProjectStore(context, dbName)
        val serviceB = ProjectTrustService(storeB)
        val record = storeB.trustRecordOf(created.id)
        assertTrue("consent must survive restart", record != null)
        assertTrue(
            "consented bytes must gate-allow after restart",
            serviceB.gate(created) is TrustVerdict.Allow
        )
    }

    @Test(timeout = 120_000)
    fun `removing the row removes the consent with it`() = runBlocking {
        val dir = File(context.filesDir, "trust-removed/repo").apply { mkdirs() }
        File(dir, "crushrc").writeText("hello")
        val store = RoomProjectStore(context, dbName)
        val ref = store.create("Trust gone", dir)
        val service = ProjectTrustService(store)
        service.recordConsent(ref)
        assertTrue(store.trustRecordOf(ref.id) != null)

        store.remove(ref.id)
        assertNull("row gone", store.byId(ref.id))
        assertNull("the consent must die WITH the row", store.trustRecordOf(ref.id))
        // The trust RECORD is gone, but the folder's crushrc FILE is user data
        // and survives a row removal (the removal contract does not touch it
        // for imported folders). So the SERVICE's current-hash still hashes
        // it, and a re-bind attempt on a fresh row starts from zero: no
        // record + a present crushrc = Required. The product re-gates; it
        // does not assume "unimportant because the folder was trusted once".
        assertTrue(
            "a removed row re-gates from zero (record gone, file still present)",
            service.gate(ref) is TrustVerdict.Required
        )
    }
}
