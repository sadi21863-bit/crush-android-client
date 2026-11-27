package com.crush.mobile.ui.screens.chat

import com.crush.mobile.engine.EngineState
import com.crush.mobile.domain.model.ReasoningDisplay

/**
 * Phase 2a (V2 plan §5.2.1, Appendix C.1): the host-resolved inputs the Chat
 * projection is allowed to consume.
 *
 * Everything here is a plain value. No Context, no Compose type, no flow, no
 * Android API. The host resolves the facts it alone can read (prewarm, provider
 * display name, the binding/key pair) and hands them to the pure projection.
 */
enum class BindingKind { Legacy, ChatPool, Project }

/**
 * Readiness is a view fact, not a second engine state.
 *
 * [Switching] is the load-bearing one: a binding/key disagreement can never be
 * rendered as [Ready]. See [ChatHostInputs.readiness].
 */
enum class EngineReadiness { Starting, Ready, Recoverable, Failed, Switching }

enum class EnterSendMode { Send, Newline }

/**
 * The free marker is a UI decision with a phase boundary.
 *
 * Phase 2a/2b.1 carry the legacy suffix rule to keep today's pixels; Phase 6
 * replaces it with the catalogue `isFree` rule (V2 plan §5.3.1).
 */
enum class FreeMarker { Shown, Hidden }

/** Host-owned trust dialog content; the host keeps the domain request. */
enum class TrustFlow { Resume, Handoff }

data class PendingTrustUi(
    val folderLabel: String,
    /** Null means the plain first-consent shape: no extra paragraph. */
    val reason: String?,
    val flow: TrustFlow,
)

/**
 * The inputs a host produces for one coherent projection pass.
 *
 * [snapshotReady] is false only for [ChatHostState.initial]. It exists so a
 * `stateIn` seed can never claim readiness before the first combined
 * binding/engine snapshot has landed.
 */
data class ChatHostInputs(
    val binding: BindingKind = BindingKind.Legacy,
    val projectLabel: String? = null,
    val instanceKey: String = "",
    val engineStateKey: String = "",
    val engineState: EngineState = EngineState.Stopped,
    val snapshotReady: Boolean = false,
    val prewarm: Boolean = false,
    val enterSendMode: EnterSendMode = EnterSendMode.Newline,
    /**
     * V2-7 (owner, 2026-11-22): whether reasoning is shown. Host-resolved from
     * persisted settings; defaulted so every existing projection construction
     * stays source-compatible.
     */
    val reasoningDisplay: ReasoningDisplay = ReasoningDisplay.COLLAPSED,
    val providerDisplayName: String = "",
    /** V2-16 A2: recovered run outcomes; host-resolved from the controller. */
    val recoveredOutcomes: List<RecoveredOutcomeView> = emptyList(),
) {

    /** The existing surface wording, preserved verbatim for 2b.1 parity. */
    val surfaceLabel: String
        get() = when (binding) {
            BindingKind.ChatPool -> "Crush Chat - conversation only"
            BindingKind.Project -> "Project - ${projectLabel.orEmpty()}"
            BindingKind.Legacy -> "Crush Chat"
        }

    /**
     * True only when the coherent snapshot is ready AND the persisted setting
     * asks for prewarm.
     *
     * The `stateIn` seed is `(snapshotReady = false, prewarm = false)`, and the
     * combine waits for the persisted settings flow. Resolving prewarm from a
     * separate collect with `initialValue = AppSettings()` instead resolves
     * AUTO on the first frame and starts the supervisor even when the user
     * saved Off - flow 23's cold-start race. The composition must gate on this
     * property, never on the seed's AUTO default.
     */
    val prewarmAtComposition: Boolean
        get() = snapshotReady && prewarm

    /**
     * The single readiness rule.
     *
     * Order matters: a key mismatch wins over every other fact, then the
     * no-snapshot seed, then a real ready engine, then the settled recovery
     * path, then a failed engine. `Ready` is unreachable while the binding and
     * the engine sample disagree.
     */
    fun readiness(engineReady: Boolean, recoverable: Boolean): EngineReadiness = when {
        engineStateKey != instanceKey -> EngineReadiness.Switching
        !snapshotReady -> EngineReadiness.Starting
        engineReady -> EngineReadiness.Ready
        recoverable -> EngineReadiness.Recoverable
        engineState is EngineState.Failed -> EngineReadiness.Failed
        else -> EngineReadiness.Starting
    }
}

/**
 * One emission of the Chat host.
 *
 * [initial] is a safe, empty, non-ready seed for `stateIn`. The first real
 * `combine` emission sets `snapshotReady = true` on [inputs].
 */
data class ChatHostState(
    val chat: ChatUiState = ChatUiState(),
    val inputs: ChatHostInputs = ChatHostInputs(),
) {
    fun toViewState(): ChatViewState = chat.toViewState(inputs)

    companion object {
        fun initial(): ChatHostState = ChatHostState()
    }
}
