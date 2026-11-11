package com.crush.mobile.data.crush

/**
 * THE ENGINE API-SURFACE CONTRACT — the compatibility half of the acceptance
 * kit. The catalogue ratchet (EngineCatalogues + the policy test) answers
 * "is every TOOL id of this engine denied?"; this object answers "does THIS
 * APP BUILD actually speak this engine's REST/SSE surface?".
 *
 * Why it exists: v0.100.0 removes `POST …/permissions/skip` and replaces the
 * workspace-create `yolo` bool with a `permission_mode` enum — breaks a
 * version bump would otherwise discover at ATTACH time on the user's device.
 * Pinning each version's surface here turns that into a named, test-visible
 * gap: `gapsFor(newest)` is literally the upgrade-work list, and it shrinks
 * only when the app's client code learns the new surface.
 *
 * Sources: upstream source + release notes as recorded in
 * docs/research-engine-v0100-2026-11-12.md (§2 breaking changes) and
 * docs/engine-acceptance-kit.md §T0. Values here are ASSERTED by
 * EngineApiContractTest — changing them is a reviewed act.
 */
object EngineApiContracts {

    /** How the app can control the workspace's permission posture. */
    enum class PermissionControl {
        /** v0.99.0: POST /v1/workspaces/{id}/permissions/skip {skip:Boolean}. */
        SKIP_ENDPOINT,

        /** v0.100.0: POST /v1/workspaces/{id}/permissions/mode {mode:enum}. */
        MODE_ENDPOINT,
    }

    /** The workspace-create field that carries the permission posture. */
    enum class WorkspacePermissionField {
        /** v0.99.0: `yolo: Boolean`. */
        YOLO_BOOL,

        /** v0.100.0: `permission_mode: "normal"|"yolo"|"sysadmin"`. */
        PERMISSION_MODE_ENUM,
    }

    data class ApiSurface(
        val version: String,
        val permissionControl: PermissionControl,
        val workspacePermissionField: WorkspacePermissionField,
        /** SSE part/event types the version ADDS that the renderer must tolerate. */
        val newSseShapes: List<String>,
    )

    /**
     * The surface THIS app build implements — CrushApi.skipPermissions
     * (v0.99.0 path), the workspace-create `yolo` field, and a renderer that
     * treats unknown SSE part types as log-and-ignore.
     */
    val IMPLEMENTED = ApiSurface(
        version = "0.99.0",
        permissionControl = PermissionControl.SKIP_ENDPOINT,
        workspacePermissionField = WorkspacePermissionField.YOLO_BOOL,
        newSseShapes = emptyList(),
    )

    /**
     * Every engine surface the kit knows. Add rows in the SAME act as the
     * EngineCatalogues row (a version is not "known" until both manifests
     * carry it — a test asserts the version sets match).
     */
    val KNOWN: List<ApiSurface> = listOf(
        IMPLEMENTED,
        ApiSurface(
            version = "0.100.0",
            permissionControl = PermissionControl.MODE_ENDPOINT,
            workspacePermissionField = WorkspacePermissionField.PERMISSION_MODE_ENUM,
            newSseShapes = listOf(
                "sub_agent_report (new message part type)",
                "pinentry/ssh prompt events (new SSE event families)",
            ),
        ),
    )

    val newest: ApiSurface get() = KNOWN.last()

    fun byVersion(version: String): ApiSurface? =
        KNOWN.firstOrNull { it.version == version }

    /**
     * The human-readable incompatibilities between what we implement and
     * [surface] — empty means this build speaks that engine's surface.
     */
    fun gapsFor(surface: ApiSurface): List<String> = buildList {
        if (surface.permissionControl != IMPLEMENTED.permissionControl) {
            add(
                "permission control: engine wants ${surface.permissionControl}, " +
                    "app speaks ${IMPLEMENTED.permissionControl}"
            )
        }
        if (surface.workspacePermissionField != IMPLEMENTED.workspacePermissionField) {
            add(
                "workspace permission field: engine wants " +
                    "${surface.workspacePermissionField}, app writes " +
                    "${IMPLEMENTED.workspacePermissionField}"
            )
        }
        surface.newSseShapes.forEach { shape ->
            add("SSE shape to tolerate on the rails: $shape")
        }
    }
}
