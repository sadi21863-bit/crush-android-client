package com.crush.mobile.data.crush

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the API-surface contract (the compatibility half of the engine
 * acceptance kit). Two jobs:
 *
 * 1. The PINNED surface (what the app implements) must stay gap-free — a
 *    drift here means the app stopped speaking its own engine of record.
 * 2. The NEWEST known surface's gaps must be EXACTLY the documented list.
 *    v0.100.0's permission endpoint/field changes are a real upgrade
 *    blocker; when the client learns the new surface, this test fails
 *    until the gap is removed deliberately — the upgrade work cannot be
 *    forgotten or silently half-done.
 *
 * The version sets of the two manifests are also asserted to match: a
 * version is only "known" when its tool catalogue AND its API surface
 * rows both exist.
 */
class EngineApiContractTest {

    @Test
    fun `the pinned surface has no gaps`() {
        val gaps = EngineApiContracts.gapsFor(EngineApiContracts.IMPLEMENTED)
        assertTrue("the app must speak its pinned engine: $gaps", gaps.isEmpty())
    }

    @Test
    fun `the v0100 gaps are exactly the documented upgrade work`() {
        val gaps = EngineApiContracts.gapsFor(EngineApiContracts.byVersion("0.100.0")!!)
        assertEquals(
            listOf(
                "permission control: engine wants MODE_ENDPOINT, app speaks SKIP_ENDPOINT",
                "workspace permission field: engine wants PERMISSION_MODE_ENUM, app writes YOLO_BOOL",
                "SSE shape to tolerate on the rails: sub_agent_report (new message part type)",
                "SSE shape to tolerate on the rails: pinentry/ssh prompt events (new SSE event families)",
            ),
            gaps,
        )
    }

    @Test
    fun `both manifests know the same versions`() {
        val catalogues = EngineCatalogues.KNOWN.map { it.version }.toSet()
        val contracts = EngineApiContracts.KNOWN.map { it.version }.toSet()
        assertEquals(
            "EngineCatalogues and EngineApiContracts must carry the same versions",
            catalogues,
            contracts,
        )
        assertTrue(
            "the pinned engine must have both rows",
            "0.99.0" in catalogues && "0.99.0" in contracts,
        )
    }
}
