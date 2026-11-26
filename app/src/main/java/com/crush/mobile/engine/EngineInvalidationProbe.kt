package com.crush.mobile.engine

/**
 * Invalidation signal shared by every engine-stop/replace/delete path.
 *
 * In production AppContainer installs the listener that bumps the per-instance
 * epoch and notifies the process-scoped ExecutionController. The listener is
 * null only in JVM tests that never construct AppContainer, where [fire] is a
 * no-op. E5-R6 installs its own recording listener to pin the barrier.
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
