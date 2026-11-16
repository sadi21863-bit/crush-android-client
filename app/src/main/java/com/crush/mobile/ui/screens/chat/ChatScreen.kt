package com.crush.mobile.ui.screens.chat

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crush.mobile.CrushMobileApp
import com.crush.mobile.data.project.PendingProjectTrust
import com.crush.mobile.di.AppContainer
import com.crush.mobile.ui.crushrcTrustNote
import com.crush.mobile.util.AppLog

/**
 * The Chat HOST (V2 plan §5.6).
 *
 * This function owns everything the content layer may not: the Context, the
 * container, the ViewModel, navigation, the engine's lifecycle effects, and the
 * domain [PendingProjectTrust] behind the trust dialog. It hands the content one
 * immutable [ChatViewState], one action sink, and the trust prompt as a value.
 *
 * The host body is otherwise preserved from the pre-2b.1 screen: the same
 * LaunchedEffects, in the same order, with the same comments. Only the subtree
 * below the Scaffold moved into [ChatContent].
 */
@Composable
fun ChatScreen(
    onOpenSettings: () -> Unit,
    onOpenProjects: () -> Unit,
    onOpenReview: () -> Unit,
    isLocked: Boolean = false
) {
    val context = LocalContext.current
    val container: AppContainer = (context.applicationContext as CrushMobileApp).container
    val vm: ChatViewModel = viewModel(factory = ChatViewModel.Factory(context, container))
    // Phase 2a's coherent snapshot is the ONLY source of renderable state: the
    // binding/key pair and the engine sample are assembled together, so content
    // can never be composed from a new binding with the previous engine's state.
    val host by vm.hostState.collectAsStateWithLifecycle()
    // The engine state of WHATEVER instance the chat layer is bound to ("" =
    // legacy engine on the process-single supervisor; "p<uuid8>" = a project's
    // own engine child). The container flips this flow when the Projects
    // surface opens a project.
    val engineState by container.engineStateForActiveInstance.collectAsStateWithLifecycle()
    val activeInstanceKey by container.activeInstanceKey.collectAsStateWithLifecycle()
    val keyAvailable by container.keyAvailable.collectAsStateWithLifecycle()

    // Tell the ViewModel the gate is closed. It cannot infer this: the lock state
    // lives in MainActivity, and composing this screen behind an overlay was
    // exactly how the engine booted and the key decrypted while locked.
    LaunchedEffect(isLocked) { vm.onLockStateChanged(isLocked) }

    // Prewarm is read from the coherent host snapshot - the SAME TuningPolicy
    // result AppContainer.applyTuning pushes into the supervisor. A separate
    // collectAsStateWithLifecycle(initialValue = AppSettings()) resolved AUTO
    // on the first frame and started the engine even when the persisted value
    // was Off (flow 23). The host seed is non-ready with prewarm=false, and the
    // combine waits for the persisted settings flow.
    val prewarm = host.inputs.prewarmAtComposition

    // The engine was never started on this path: only ChatViewModel touched the
    // supervisor for setWorkActive, so the chat screen sat on "engine stopped"
    // forever. Start it here, and deliberately do NOT stop it on dispose so
    // navigating away cannot invalidate an in-flight session.
    //
    // This now honours the prewarm setting, which this screen used to write and
    // then ignore - the row was a dead control for as long as it existed. With
    // prewarm OFF the boot is deferred to the first keystroke (see the composer's
    // onChange below) instead of happening at composition.
    //
    // The deferral is NOT free and the cost is deliberate. Nothing else in the
    // app calls start(), and send() returns silently while the port is still
    // null, so a cold engine cannot be recovered from by the send path itself -
    // which means the composer has to stay typeable or the screen is simply
    // dead with no way out. The price is a first message that waits for the
    // ~3s boot instead of meeting a warm engine, paid for ~59MB of RAM not being
    // held on a device that may never chat. This must NOT be "simplified" back
    // into an unconditional start().
    // START the ACTIVE engine instance when composing (prewarm on), and again
    // every time the ACTIVE INSTANCE changes (ADR-0001 slice 2, 2026-11-03).
    // bindProject alone cannot start the new instance's engine, and nothing
    // else in the app calls start() except the composer's first keystroke -
    // without this the project chat sat on "engine stopped" with no way out
    // (measured 21:39 on device, test-project). With prewarm OFF the boot
    // defers to the first keystroke instead, which targets the bound instance
    // too (vm.startEngine()).
    LaunchedEffect(activeInstanceKey, host.inputs.snapshotReady, prewarm) {
        vm.bindProject()
        // The seed carries prewarm=false and snapshotReady=false. Returning here
        // is what keeps a persisted Off from being overwritten by AUTO on the
        // first frame; the effect re-runs when the real settings land.
        if (!host.inputs.snapshotReady) return@LaunchedEffect
        if (prewarm) {
            AppLog.i("Chat", "starting supervisor for instance '$activeInstanceKey' (prewarm on)")
            vm.startEngine()
        } else {
            AppLog.i("Chat", "prewarm off; supervisor deferred to first input for $activeInstanceKey")
        }
    }

    LaunchedEffect(engineState) { vm.bindEngine(engineState) }    // Provider is chosen on the Settings screen, which has its own ViewModel.
    LaunchedEffect(Unit) { vm.observeProviderChanges() }
    // An unlock is the only event that can make a blocked bootstrap retryable;
    // engine state does not change when the user authenticates.
    LaunchedEffect(keyAvailable) { vm.onKeyAvailable() }

    // The content projection is built once per coherent host snapshot.
    val view = remember(host) { host.chat.toViewState(host.inputs) }

    // The F04 trust request stays a DOMAIN object here; content only ever sees
    // [PendingTrustUi]. The origin remembers which entry raised the gate so an
    // Allow can re-call it, mode and arguments included.
    var pendingTrust by remember { mutableStateOf<PendingProjectTrust?>(null) }
    var trustOrigin by remember { mutableStateOf<TrustOrigin?>(null) }

    fun retryTrust(origin: TrustOrigin) {
        val onTrustRequired: (PendingProjectTrust) -> Unit = { request ->
            pendingTrust = request
            trustOrigin = origin
        }
        when (origin) {
            TrustOrigin.Resume, TrustOrigin.OpenInCode ->
                vm.openHandoffInCode(onTrustRequired)
            is TrustOrigin.Confirm ->
                if (origin.mode == HandoffSheetMode.DISPATCHED) {
                    vm.openHandoffInCode(onTrustRequired)
                } else {
                    vm.confirmHandoff(origin.summary, origin.destinationId, onTrustRequired)
                }
        }
    }

    // §5.4.2: the composer's onChange crosses the seam as DraftChanged, and the
    // host's wrapper applies the gate. `rememberDraftCallback` reads canType and
    // prewarm through rememberUpdatedState, so the lambda content captured in an
    // earlier composition still decides with the CURRENT snapshot - never with
    // the composition-time values. T2B1-04 pins all four freshness rows.
    val onDraft = rememberDraftCallback(
        canType = view.composer.canType,
        prewarm = host.inputs.prewarm,
        startEngine = vm::startEngine,
        send = { action -> vm.dispatch(action) },
    )

    val onAction: (ChatAction) -> Unit = { action ->
        if (action is ChatAction.DraftChanged) {
            onDraft(action.text)
        } else {
            when (val effect = vm.dispatch(action)) {
                ChatHostEffect.None -> Unit
                is ChatHostEffect.Navigate -> when (effect.destination) {
                    HostDestination.Settings -> onOpenSettings()
                    HostDestination.Projects -> onOpenProjects()
                    HostDestination.Review -> onOpenReview()
                }

                is ChatHostEffect.ConfirmHandoff -> {
                    // The sheet exists at this point (the action came from it).
                    // The mode is captured WITH the request: an Allow must
                    // re-call exactly the entry that was refused.
                    val mode = vm.state.value.handoffSheet
                        ?.let(::handoffSheetMode)
                        ?: HandoffSheetMode.DRAFT
                    val origin = TrustOrigin.Confirm(
                        summary = effect.summary,
                        destinationId = effect.destinationId,
                        mode = mode,
                    )
                    vm.confirmHandoff(effect.summary, effect.destinationId) { request ->
                        pendingTrust = request
                        trustOrigin = origin
                    }
                }

                ChatHostEffect.OpenHandoffInCode -> {
                    // Two content paths share this action: the resume bar (no
                    // sheet) and the sheet's jump. The flow name follows the
                    // sheet's presence; the retry is the same entry either way.
                    val origin = if (vm.state.value.handoffSheet != null) {
                        TrustOrigin.OpenInCode
                    } else {
                        TrustOrigin.Resume
                    }
                    vm.openHandoffInCode { request ->
                        pendingTrust = request
                        trustOrigin = origin
                    }
                }

                is ChatHostEffect.GrantTrust ->
                    // No content path currently dispatches this: the dialog's
                    // Allow is executed above, with its retry continuation.
                    // Kept explicit so the effect table has no silent branch.
                    vm.grantHandoffTrust(effect.ref) {}
            }
        }
    }

    ChatContent(
        state = view,
        onAction = onAction,
        pendingTrust = pendingTrust?.let { request ->
            PendingTrustUi(
                folderLabel = request.ref.label,
                reason = crushrcTrustNote(request.kind),
                flow = if (host.chat.handoffSheet != null) TrustFlow.Handoff else TrustFlow.Resume,
            )
        },
        onTrustConfirm = {
            val request = pendingTrust
            val origin = trustOrigin
            if (request != null && origin != null) {
                pendingTrust = null
                if (origin == TrustOrigin.Resume) {
                    // PARITY (pre-existing defect, deliberately pinned): the
                    // pre-2b.1 resume-bar dialog re-called openHandoffInCode
                    // WITHOUT recording consent on the chat side, so a crushrc
                    // that changed after dispatch re-opened it forever. 2b.1 is
                    // an extraction, not a defect-fix batch (the same rule that
                    // pins flow 13's unconfirmed delete), so this path keeps
                    // today's exact sequence. Recorded in the ledger.
                    retryTrust(origin)
                } else {
                    // The sheet and jump paths (and the old pendingHandoffTrust
                    // block): record consent for the request's CURRENT bytes
                    // first (the service re-hashes at record time), then
                    // re-call - the second gate finds Allow and the flow
                    // continues. Whatever moved AGAIN inside the re-call's gate
                    // re-opens the dialog.
                    vm.grantHandoffTrust(request.ref) {
                        retryTrust(origin)
                    }
                }
            } else {
                pendingTrust = null
            }
        },
        onTrustDismiss = {
            pendingTrust = null
            trustOrigin = null
        },
    )
}

/**
 * §5.4.2 + Appendix C.2: the host's draft-callback wrapper.
 *
 * The lambda is remembered ONCE, so content keeps a stable callback across
 * recompositions; every decision it makes reads the latest values through
 * `rememberUpdatedState`. A captured composition snapshot is never consulted.
 */
@Composable
internal fun rememberDraftCallback(
    canType: Boolean,
    prewarm: Boolean,
    startEngine: () -> Unit,
    send: (ChatAction) -> Unit,
): (String) -> Unit {
    val canTypeState = rememberUpdatedState(canType)
    val prewarmState = rememberUpdatedState(prewarm)
    val startEngineState = rememberUpdatedState(startEngine)
    val sendState = rememberUpdatedState(send)

    return remember<(String) -> Unit> {
        { text: String ->
            ChatDispatch.draft(
                text = text,
                canType = canTypeState.value,
                prewarm = prewarmState.value,
                startEngine = { startEngineState.value() },
                send = { action -> sendState.value(action) },
            )
        }
    }
}

/**
 * Which host entry raised the trust gate. `Confirm` carries the mode and the
 * exact arguments so Allow re-calls the same decision, not a lookalike.
 */
private sealed interface TrustOrigin {
    data object Resume : TrustOrigin
    data object OpenInCode : TrustOrigin
    data class Confirm(
        val summary: String,
        val destinationId: String?,
        val mode: HandoffSheetMode,
    ) : TrustOrigin
}
