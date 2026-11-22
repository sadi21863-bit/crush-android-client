package com.crush.mobile.domain.model

/**
 * V2-7 (owner, 2026-11-22): whether the model's reasoning is shown.
 *
 * Two states, not three. `OFF` means the transcript never shows reasoning
 * anywhere, and the app also discards it before it reaches
 * [com.crush.mobile.ui.screens.chat.ChatMessage] or history replay, so it
 * cannot reappear. Stopping the model from generating reasoning is a provider
 * concern, not this setting.
 */
enum class ReasoningDisplay {
    /** Draw a closed disclosure above the reply; the user can open it. Default. */
    COLLAPSED,

    /**
     * Never show reasoning and discard it at the transcript boundary. The model
     * may still produce it; the app drops it on arrival.
     */
    OFF;

    /** Serialised form for settings storage. */
    val stored: String get() = name.lowercase()

    companion object {
        /**
         * Unknown or absent values fail safe to [COLLAPSED]. `"hidden"` is
         * accepted as [OFF] because an unreleased development build wrote that
         * middle state; it must not resurrect a block the owner removed.
         */
        fun parse(raw: String?): ReasoningDisplay = when (raw) {
            "off", "hidden" -> OFF
            else -> COLLAPSED
        }
    }
}
