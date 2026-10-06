package com.opencode.chat.engine

/** Observable lifecycle of the embedded engine. */
sealed interface EngineState {

    data object Stopped : EngineState

    data class Starting(val attempt: Int) : EngineState

    /** Serving HTTP and answering /v1/health. */
    data class Running(
        val pid: Int,
        val port: Int,
        val version: String,
        val bootMs: Long
    ) : EngineState

    /**
     * Not running. [willRetry] is false once the supervisor gives up, so the
     * UI can show a real error instead of an infinite spinner.
     */
    data class Failed(
        val reason: String,
        val detail: String? = null,
        val attempt: Int = 1,
        val willRetry: Boolean = true
    ) : EngineState
}

val EngineState.isUsable: Boolean get() = this is EngineState.Running

val EngineState.port: Int? get() = (this as? EngineState.Running)?.port