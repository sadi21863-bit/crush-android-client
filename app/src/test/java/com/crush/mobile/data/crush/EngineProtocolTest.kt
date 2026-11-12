package com.crush.mobile.data.crush

import com.crush.mobile.domain.model.PermissionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the version-aware protocol adapter that lets one build speak both the
 * v0.99.0 and v0.100.0 permission wires (EngineProtocol). The engine version
 * is the ONLY selector; unknown versions fail safe to the pinned family.
 */
class EngineProtocolTest {

    @Test
    fun `versions parse with prefixes and suffixes`() {
        assertEquals(Triple(0, 100, 0), EngineProtocol.parseVersion("0.100.0"))
        assertEquals(Triple(0, 100, 0), EngineProtocol.parseVersion("v0.100.0"))
        assertEquals(Triple(0, 99, 0), EngineProtocol.parseVersion("0.99.0+dirty"))
        // THE MEASURED FORM (2026-11-12): EngineState.Running.version carries
        // the engine's whole JSON claim, not a bare semver.
        assertEquals(
            Triple(0, 100, 0),
            EngineProtocol.parseVersion("{\"version\":\"v0.100.0\",\"commit\":\"unknown\",\"build_id\":\"x\"}")
        )
        assertEquals(
            Triple(0, 99, 0),
            EngineProtocol.parseVersion("{\"version\":\"v0.99.0\",\"commit\":\"unknown\"}")
        )
        assertNull(EngineProtocol.parseVersion(""))
        assertNull(EngineProtocol.parseVersion(null))
        assertNull(EngineProtocol.parseVersion("nightly"))
    }

    @Test
    fun `wire family flips at 0_100_0`() {
        assertEquals(
            EngineProtocol.PermissionWire.SKIP_FLAG,
            EngineProtocol.permissionWireFor("0.99.0")
        )
        assertEquals(
            EngineProtocol.PermissionWire.SKIP_FLAG,
            EngineProtocol.permissionWireFor("0.99.9")
        )
        assertEquals(
            EngineProtocol.PermissionWire.MODE_ENUM,
            EngineProtocol.permissionWireFor("0.100.0")
        )
        assertEquals(
            EngineProtocol.PermissionWire.MODE_ENUM,
            EngineProtocol.permissionWireFor("v0.100.3")
        )
        assertEquals(
            EngineProtocol.PermissionWire.MODE_ENUM,
            EngineProtocol.permissionWireFor("0.101.0")
        )
        // Fail safe: unknown/absent versions speak the PINNED family — the
        // engine this build actually ships (v0.99.0 on main, v0.100.0 on the
        // candidate branch). Asserting against the pin keeps this test honest
        // across a version bump instead of hardcoding yesterday's family.
        val pinnedFamily = EngineProtocol.permissionWireFor(EngineCatalogues.PINNED_VERSION)
        assertEquals(pinnedFamily, EngineProtocol.permissionWireFor(null))
        assertEquals(pinnedFamily, EngineProtocol.permissionWireFor("nightly"))
    }

    @Test
    fun `mode mapping never sends sysadmin`() {
        assertEquals("normal", EngineProtocol.modeFor(PermissionMode.ASK))
        assertEquals("yolo", EngineProtocol.modeFor(PermissionMode.AUTO_APPROVE))
    }

    @Test
    fun `is at least compares numerically`() {
        assertTrue(EngineProtocol.isAtLeast("0.100.0", 0, 100, 0))
        assertFalse(EngineProtocol.isAtLeast("0.99.0", 0, 100, 0))
        assertTrue(EngineProtocol.isAtLeast("1.0.0", 0, 100, 0))
        assertFalse(EngineProtocol.isAtLeast(null, 0, 100, 0))
    }
}
