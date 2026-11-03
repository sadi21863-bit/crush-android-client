package com.crush.mobile.data.project

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/**
 * All reads/writes for the projects table. ByPath exists so the legacy
 * workspace migration (`RoomProjectStore.migrateLegacyWorkspace`) and any
 * future "attach by path" flow never have to scan the whole table in memory.
 */
@Dao
interface ProjectDao {

    /** Insert-or-replace by primary key. Rows are immutable identity here. */
    @Upsert
    suspend fun upsert(project: ProjectEntity)

    @Query("SELECT * FROM projects ORDER BY created_at ASC")
    suspend fun all(): List<ProjectEntity>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun byId(id: String): ProjectEntity?

    @Query("SELECT * FROM projects WHERE repo_dir = :path LIMIT 1")
    suspend fun byPath(path: String): ProjectEntity?
}
