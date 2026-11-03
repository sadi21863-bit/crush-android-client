package com.crush.mobile.project

import java.io.File
import java.util.UUID

/**
 * The durable seam for project identity (ADR-0001 §5, canonical persistence;
 * plan P2 "ProjectRegistry" persistence).
 *
 * Two implementations and one contract:
 * - [ProjectRegistry]: the in-memory prototype - process-lifetime only, kept
 *   as the zero-machinery fixture for the integration proofs.
 * - `com.crush.mobile.data.project.RoomProjectStore` (P2 Room, 2026-11-03):
 *   the real store, surviving process death, with the same three operations.
 *
 * Nothing here knows about storage time, schema, or migrations: those live in
 * the implementation, and the ADR's "additive migration" invariant means the
 * legacy `filesDir/workspace` path must be representable as one row - see
 * `RoomProjectStore.migrateLegacyWorkspace`.
 */
interface ProjectStore {

    /** Creates (or re-uses, path-keyed lives in the impl) a project entry. */
    suspend fun create(label: String, repoDir: File): ProjectRef

    /** All projects, stable order (insertion for both implementations). */
    suspend fun list(): List<ProjectRef>

    /** Canonical lookup by identity; null when unknown. */
    suspend fun byId(id: UUID): ProjectRef?
}
