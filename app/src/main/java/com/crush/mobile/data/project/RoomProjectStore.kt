package com.crush.mobile.data.project

import android.content.Context
import androidx.room.Room
import com.crush.mobile.data.handoff.HandoffDao
import com.crush.mobile.project.ProjectRef
import com.crush.mobile.project.ProjectStore
import java.io.File
import java.util.UUID

/**
 * The durable [ProjectStore] (ADR-0001 P2): the in-memory prototype's
 * contract, backed by Room. Survives process death; that survival is proven
 * on device by `ProjectStoreRoomPersistenceTest`, not claimed here.
 *
 * Deliberately NOT wired into the DI graph yet: the Projects surface (P3
 * productization, ADR-0001 amendment) is the consumer this was built for,
 * and wiring a store with no reader would be untestable-in-use. The legacy
 * app path (`filesDir/workspace`) stays untouched; adoption is additive per
 * the ADR's migration invariant.
 */
class RoomProjectStore(
    context: Context,
    /**
     * Overridable so the on-device persistence test uses its own database
     * file and never competes with (or wipes) the app's real registry.
     */
    dbName: String = "projects.db",
) : ProjectStore, ProjectTrustSource {

    private val database: ProjectDatabase =
        Room.databaseBuilder(context.applicationContext, ProjectDatabase::class.java, dbName)
            .addMigrations(ProjectDatabase.MIGRATION_1_2, ProjectDatabase.MIGRATION_2_3)
            .build()

    private val dao: ProjectDao = database.projectDao()

    /**
     * The P6 handoff trace surface (Batch A, 2026-11-08): rides THIS same
     * database file, builder and migration chain - one databaseBuilder for
     * the projects+handoffs file, no second store, no re-registered
     * migration list racing the first (plan a4). Consumed through
     * `AppContainer.projectStore`; the DI seam itself stays the store.
     */
    fun handoffDao(): HandoffDao = database.handoffDao()

    override suspend fun create(label: String, repoDir: File): ProjectRef {
        val dir = repoDir.apply { mkdirs() }
        val entity = ProjectRef(UUID.randomUUID(), label, dir).toEntity(now())
        dao.upsert(entity)
        return entity.toRef()
    }

    override suspend fun list(): List<ProjectRef> = dao.all().map { it.toRef() }

    override suspend fun byId(id: UUID): ProjectRef? = dao.byId(id.toString())?.toRef()

    /**
     * Reads by the field the ENGINE keys workspaces on. Returns null when no
     * project claims this path yet.
     */
    suspend fun byPath(path: String): ProjectRef? = dao.byPath(path)?.toRef()

    /**
     * Drops the ROW only - the plan §8 remove-from-list vs delete-local-copy
     * distinction, resolved per row TYPE by the caller (see
     * ProjectsViewModel.removeProject): an imported (external) folder is
     * USER DATA and never touched by a removal, period; an app-minted
     * internal repo is app-owned hidden data and is deleted together with
     * its row by the same caller that owns that lifecycle decision.
     *
     * S-02 invariants carried HERE, in the same statement: the row carries
     * the crushrc trust record (S-02 columns, v1->v2), so deleting the row
     * deletes the consent - there is no separate trust store to clear and
     * no two-step ordering between row death and record death. Re-importing
     * the same folder later mints a fresh row id that starts with NO record,
     * which is exactly the fresh-consent contract.
     */
    suspend fun remove(id: UUID) {
        dao.deleteById(id.toString())
    }

    /**
     * The ADR's additive-migration invariant, as a named operation: the
     * legacy workspace (`filesDir/workspace`, [WorkspaceManager]-era) is
     * representable as a project row without touching its history. Idempotent
     * BY PATH - a re-run re-reads the existing row rather than minting a twin.
     *
     * Concurrency note: two simultaneous first-calls could mint two rows with
     * the same path (create is not serialized here yet). Registry writes are
     * UI-rare single-actor today; the multi-writer guard belongs to P3's
     * Projects surface, where the write becomes a user action.
     */
    suspend fun migrateLegacyWorkspace(legacyDir: File): ProjectRef {
        val path = legacyDir.absolutePath
        return byPath(path) ?: create(label = "Legacy workspace", repoDir = legacyDir)
    }

    // ----------------------------------------------------------------
    // ProjectTrustSource (S-02, 2026-11-05): the row-backed half.
    // ----------------------------------------------------------------

    /**
     * Existence marker is [ProjectEntity.consentedAtMs]: a row never trusted
     * has BOTH trust columns null and reads as null (no record); a trusted
     * no-crushrc row has a stamp with a null fingerprint. A
     * hypothetically-corrupt row (fingerprint but NO timestamp) also reads
     * as null - fail-to-no-record, which re-gates, never fail-to-trusted.
     */
    override suspend fun trustRecordOf(id: UUID): ProjectTrustRecord? {
        val row = dao.byId(id.toString()) ?: return null
        val stamp = row.consentedAtMs ?: return null
        return ProjectTrustRecord(fingerprint = row.trustFingerprint, consentedAtMs = stamp)
    }

    /** Single targeted row UPDATE; the columns are null to record no-crushrc trust. */
    override suspend fun writeTrustRecord(id: UUID, fingerprint: String?, consentedAtMs: Long) {
        dao.updateTrust(id.toString(), fingerprint, consentedAtMs)
    }

    private fun now(): Long = System.currentTimeMillis()
}
