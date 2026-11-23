package com.crush.mobile.ui.screens.chat

import com.crush.mobile.project.ProjectRef

/**
 * Phase 2a (V2 plan §5.4): every Chat interaction that crosses the
 * content/host seam.
 *
 * Content-local state (sheet visibility, handoff summary/destination edits)
 * deliberately does not appear here; it stays `remember`-scoped in the
 * content layer. Permission decisions carry a request id because the engine
 * can replace the pending request between the tap and the dispatch.
 */
sealed interface ChatAction {

    data class DraftChanged(val text: String) : ChatAction
    data object Send : ChatAction
    data object Stop : ChatAction

    data object NewChat : ChatAction
    data object OpenSessions : ChatAction
    data object OpenModels : ChatAction
    data object OpenReview : ChatAction

    data class SelectModel(val id: String) : ChatAction
    data object ReloadModels : ChatAction

    data class SelectSession(val id: String) : ChatAction
    data class DeleteSession(val id: String) : ChatAction

    data class AllowOnce(val requestId: String) : ChatAction
    data class AllowForSession(val requestId: String) : ChatAction
    data class DenyPermission(val requestId: String) : ChatAction

    data object ReleaseQuestion : ChatAction
    /** A12: answers for every prompt in the open batch. */
    data class SubmitQuestion(val answers: List<AskAnswerDraft>) : ChatAction

    data class Retry(val text: String) : ChatAction
    data class UsePlanInCode(val messageIndex: Int) : ChatAction

    data class ConfirmHandoff(val destinationId: String?, val summary: String) : ChatAction
    data object DiscardHandoff : ChatAction
    data object CloseHandoffSheet : ChatAction
    data object OpenHandoffInCode : ChatAction
    data class GrantHandoffTrust(val ref: ProjectRef) : ChatAction
}

/** Navigation targets the host owns; the Chat ViewModel never navigates. */
enum class HostDestination { Review }

/**
 * An explicit host continuation.
 *
 * `dispatch` never returns `Unit`: every action either mutates ViewModel state
 * (explicit [None]) or asks the host to do something it alone can do. The
 * handoff continuations are host-owned because their `onTrustRequired`
 * callback must open the host's trust dialog; dropping them would be a silent
 * loss of consent.
 */
sealed interface ChatHostEffect {
    data object None : ChatHostEffect
    data class Navigate(val destination: HostDestination) : ChatHostEffect
    data class ConfirmHandoff(val summary: String, val destinationId: String?) : ChatHostEffect
    data object OpenHandoffInCode : ChatHostEffect
    data class GrantTrust(val ref: ProjectRef) : ChatHostEffect
}
