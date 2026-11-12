package com.crush.mobile.data.crush

import com.crush.mobile.domain.model.PermissionMode

/**
 * THE VERSION-AWARE ENGINE PROTOCOL ADAPTER — the piece that lets ONE app
 * build speak BOTH the v0.99.0 and the v0.100.0 permission surfaces, chosen
 * from the engine version observed at bind (never a build-time assumption, so
 * a rollback binary still speaks its own protocol).
 *
 * The v0.100.0 breaking changes this closes (primary-source research
 * 2026-11-12, pinned by EngineApiContracts):
 *  - workspace create: `yolo: Boolean` became `permission_mode: enum`
 *    ("normal" | "yolo" | "sysadmin");
 *  - `POST …/permissions/skip` was REMOVED in favour of
 *    `POST …/permissions/mode`.
 *
 * Sysadmin Mode is deliberately UNREACHABLE from this app: the app's
 * [PermissionMode] has no such value, and [modeFor] maps only the two modes
 * the product exposes. "sysadmin" exists in the wire enum only as a value we
 * never send.
 */
object EngineProtocol {

    /** Which permission wire family an engine version speaks. */
    enum class PermissionWire {
        /** v0.99.0: create `yolo` bool + POST /permissions/skip. */
        SKIP_FLAG,

        /** v0.100.0+: create `permission_mode` enum + POST /permissions/mode. */
        MODE_ENUM,
    }

    /**
     * Parses "0.100.0", "v0.100.0", or a suffixed build string ("0.99.0+dirty")
     * into a comparable triple; null when no leading semver triple exists.
     */
    fun parseVersion(raw: String?): Triple<Int, Int, Int>? {
        val cleaned = raw?.trim()?.removePrefix("v") ?: return null
        val m = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)").find(cleaned) ?: return null
        return Triple(
            m.groupValues[1].toInt(),
            m.groupValues[2].toInt(),
            m.groupValues[3].toInt(),
        )
    }

    fun isAtLeast(raw: String?, major: Int, minor: Int, patch: Int): Boolean {
        val v = parseVersion(raw) ?: return false
        return v.first > major ||
            (v.first == major && (v.second > minor ||
                (v.second == minor && v.third >= patch)))
    }

    /**
     * The v0.100.0 `permission_mode` enum value for an app permission mode.
     * Never returns "sysadmin" — that surface is not exposed by this product.
     */
    fun modeFor(mode: PermissionMode): String = when (mode) {
        PermissionMode.ASK -> "normal"
        PermissionMode.AUTO_APPROVE -> "yolo"
    }

    /**
     * The wire family for an observed engine version. An unparseable/absent
     * version fails SAFE to the pinned family (the shipped engine is v0.99.0):
     * the create call keeps the old shape, which is exactly what that engine
     * understands.
     */
    fun permissionWireFor(engineVersion: String?): PermissionWire =
        if (isAtLeast(engineVersion, 0, 100, 0)) PermissionWire.MODE_ENUM
        else PermissionWire.SKIP_FLAG
}
