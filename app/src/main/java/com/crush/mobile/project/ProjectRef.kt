package com.crush.mobile.project

import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory PROTOTYPE of the durable project identity proposed by
 * docs/adr-0001-project-workspace-identity.md (P2 gate of the roadmap,
 * docs/crush-plan-2026-11-02.md §23 sprint). Deliberately minimal: this file
 * exists so the two-project attachment proof can be written against a real
 * type instead of raw path strings, and so P2's Room persistence has a
 * named seam to implement. Do NOT grow UI or persistence into it until the
 * ADR is accepted and the no-bleed integration test is on record.
 *
 * Field names follow the ADR's entity table; [workspacePath] is the field
 * the ENGINE actually keys a logical workspace on (WorkspaceManager: the
 * posted id is derived from the path, and re-POSTing the same path is an
 * idempotent re-attach - measured on device through
 * TwoProjectAttachTest, 2026-11-03).
 */
data class ProjectRef(
    val id: UUID,
    val label: String,
    val repoDir: File,
    val environmentLevel: EnvironmentLevel = EnvironmentLevel.L0_EMBEDDED,
) {

    /**
     * The canonical directory handed to the engine as the workspace cwd.
     * It lives under filesDir/projects/<uuid>/repo per the ADR's invariants.
     */
    val workspacePath: String
        get() = repoDir.absolutePath

    enum class EnvironmentLevel {
        L0_EMBEDDED,
        L1_TERMUX_TOOLS,
        L2_TERMUX_PROJECT,
        L3_TERMUX_AGENT,
    }
}

/**
 * Registry PROTOTYPE: PROCESS-LIFETIME ONLY (in memory, no persistence).
 * A crashed process forgets projects here - the recovery story belongs to
 * the P2 Room migration, not to this file. Single-writer discipline stays
 * in the view layer (sessionViewEpoch etc.); this object only names things.
 */
object ProjectRegistry {

    private val projects = ConcurrentHashMap<UUID, ProjectRef>()

    /** Creates (or re-uses) a project for a canonical repo directory. */
    fun create(label: String, repoDir: File): ProjectRef {
        val dir = repoDir.apply { mkdirs() }
        val ref = ProjectRef(UUID.randomUUID(), label, dir)
        projects[ref.id] = ref
        return ref
    }

    fun list(): List<ProjectRef> = projects.values.toList()

    fun byId(id: UUID): ProjectRef? = projects[id]
}
