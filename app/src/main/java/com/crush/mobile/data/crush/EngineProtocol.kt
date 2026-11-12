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
     * Parses the engine's version claim. The engine reports its version as a
     * JSON OBJECT string in [com.crush.mobile.engine.EngineState.Running]
     * (`{"version":"v0.100.0","commit":"unknown",...}` — measured on device
     * 2026-11-12: the first rails run 404'd on /permissions/skip because this
     * parser only accepted a LEADING semver), so the JSON form is tried first;
     * plain "0.100.0" / "v0.100.0" / "0.99.0+dirty" forms follow.
     * Null when no version is recoverable.
     */
    fun parseVersion(raw: String?): Triple<Int, Int, Int>? {
        val text = raw?.trim() ?: return null
        val json = Regex("\"version\"\\s*:\\s*\"v?([0-9][0-9.]*)\"").find(text)
        if (json != null) {
            return leadingTriple(json.groupValues[1])
        }
        return leadingTriple(text.removePrefix("v"))
    }

    private fun leadingTriple(text: String): Triple<Int, Int, Int>? {
        val m = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)").find(text) ?: return null
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
     * The wire family for an observed engine version. An absent OR unparseable
     * claim (`null`, `"nightly"`, a shape a future engine invents) fails SAFE to
     * the PINNED family (EngineCatalogues.PINNED_VERSION — the engine this build
     * actually ships), not to a hardcoded family: on the v0.99.0 pin that means
     * the old `yolo`+skip shape, on the v0.100.0 pin the
     * `permission_mode`+mode shape. Hardcoding SKIP_FLAG, or letting
     * `isAtLeast("nightly") == false` choose it, was a latent live 404 on the
     * v0.100.0 candidate whenever the version claim was unreadable.
     */
    fun permissionWireFor(engineVersion: String?): PermissionWire {
        val effective = engineVersion?.takeIf { parseVersion(it) != null }
            ?: EngineCatalogues.PINNED_VERSION
        return if (isAtLeast(effective, 0, 100, 0)) {
            PermissionWire.MODE_ENUM
        } else {
            PermissionWire.SKIP_FLAG
        }
    }
}
