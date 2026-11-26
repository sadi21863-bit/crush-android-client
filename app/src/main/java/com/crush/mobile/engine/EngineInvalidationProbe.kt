package com.crush.mobile.engine

/**
 * Test-only seam for the E5-R6 invalidation barrier.
 *
 * The listener is null in production, so [fire] is a no-op and no behavior
 * changes. Tests install a listener that records the source and bumps a
 * per-instance epoch before an engine is stopped/replaced/deleted.
 *
 * This is NOT the execution controller and NOT the invalidation guard itself:
 * it only makes the existing production invalidation paths observable so a
 * deterministic test can interleave them with a provisional attachment.
 */
object EngineInvalidationProbe {
    internal var listener: ((source: String, key: String) -> Unit)? = null

    fun fire(source: String, key: String) {
        listener?.invoke(source, key)
    }
}
