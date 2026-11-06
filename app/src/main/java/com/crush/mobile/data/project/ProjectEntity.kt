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
 *
 * The S-02 crushrc trust record (2026-11-05, additively migrated v1->v2)
 * rides the row ITSELF - there is no separate trust table, so "remove the
 * project" removes the consent with the same statement that removes the
 * row (crash-atomic with it, no ordering hazard between two stores). The
 * record exists if and only if [consentedAtMs] is non-null; a record whose
 * [trustFingerprint] IS null is the trusted no-crushrc state (see
 * [RoomProjectStore]/ProjectTrustService). Both columns are null on every
 * write that does not carry a trust decision, which keeps the old row shape
 * byte-compatible for everyone but the trust service.
 */
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "label") val label: String,
    @ColumnInfo(name = "repo_dir") val repoDir: String,
    @ColumnInfo(name = "environment_level") val environmentLevel: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,

    /** null record = never trusted; null fingerprint WITH a timestamp = trusted no-crushrc. */
    @ColumnInfo(name = "trust_fingerprint") val trustFingerprint: String? = null,
    @ColumnInfo(name = "consented_at_ms") val consentedAtMs: Long? = null,
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
