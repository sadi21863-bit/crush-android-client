package com.crush.mobile.data.project

import android.content.Context
import androidx.room.Room
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
) : ProjectStore {

    private val dao: ProjectDao =
        Room.databaseBuilder(context.applicationContext, ProjectDatabase::class.java, dbName)
            .build()
            .projectDao()

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

    private fun now(): Long = System.currentTimeMillis()
}
