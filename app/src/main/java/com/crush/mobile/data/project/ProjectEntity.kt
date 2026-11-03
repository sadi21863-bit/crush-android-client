package com.crush.mobile.data.project

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.crush.mobile.project.ProjectRef
import java.io.File
import java.util.UUID

/**
 * The persisted form of [ProjectRef] (ADR-0001 §5). One row per project; the
 * workspace path is stored verbatim because the ENGINE keys the logical
 * workspace on it (WorkspaceManager derives the id from the path bytes), so
 * round-tripping it byte-exact is what keeps a restored project attached to
 * the same logical workspace.
 *
 * Timestamps are registry metadata (when the row was minted / last touched),
 * not part of [ProjectRef] - identity types stay free of lifecycle bookkeeping.
 */
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "label") val label: String,
    @ColumnInfo(name = "repo_dir") val repoDir: String,
    @ColumnInfo(name = "environment_level") val environmentLevel: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

internal fun ProjectEntity.toRef(): ProjectRef = ProjectRef(
    id = UUID.fromString(id),
    label = label,
    repoDir = File(repoDir),
    environmentLevel = runCatching {
        ProjectRef.EnvironmentLevel.valueOf(environmentLevel)
    }.getOrDefault(ProjectRef.EnvironmentLevel.L0_EMBEDDED),
)

internal fun ProjectRef.toEntity(now: Long): ProjectEntity = ProjectEntity(
    id = id.toString(),
    label = label,
    repoDir = repoDir.absolutePath,
    environmentLevel = environmentLevel.name,
    createdAt = now,
    updatedAt = now,
)
