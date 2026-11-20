package com.crush.mobile.ui.screens.chat

/** The exact permission decision an action represents. */
data class PermissionDecision(val allow: Boolean, val forSession: Boolean)

/**
 * The two defect-prone dispatch closures, kept pure so plain JUnit can pin
 * them without a ViewModel, a Context, or Compose.
 */
object ChatDispatch {

    /**
     * The composer's first-keystroke gate.
     *
     * Returns false WITHOUT dispatching and WITHOUT starting the engine when
     * the field is disabled. This is the fix for the Revision 2 wrapper whose
     * disabled branch was empty: the gate must stop both side effects, not
     * just the visible edit.
     *
     * @return true when the change was accepted.
     */
    fun draft(
        text: String,
        canType: Boolean,
        prewarm: Boolean,
        startEngine: () -> Unit,
        send: (ChatAction) -> Unit,
    ): Boolean {
        if (!canType) return false
        if (!prewarm) startEngine()
        send(ChatAction.DraftChanged(text))
        return true
    }

    /**
     * Actions the host must execute.
     *
     * Null means the action is ViewModel-owned. The host-owned set includes
     * ConfirmHandoff because its trust callback can only be owned by the host;
     * returning null there would silently discard the consent prompt.
     */
    fun hostEffect(action: ChatAction): ChatHostEffect? = when (action) {
        ChatAction.OpenReview -> ChatHostEffect.Navigate(HostDestination.Review)
        is ChatAction.ConfirmHandoff ->
            ChatHostEffect.ConfirmHandoff(action.summary, action.destinationId)
        ChatAction.OpenHandoffInCode -> ChatHostEffect.OpenHandoffInCode
        is ChatAction.GrantHandoffTrust -> ChatHostEffect.GrantTrust(action.ref)
        else -> null
    }

    /**
     * Dismissing the permission dialog is a DENY, never an approval.
     *
     * Phase 2b.1: the content layer wires both the dialog's dismiss request and
     * its visible Deny button through this one function, so the two paths
     * cannot diverge. Plain JUnit can pin it (T2B1-02).
     */
    fun permissionDismiss(requestId: String): ChatAction = ChatAction.DenyPermission(requestId)

    /**
     * The three permission actions map to exactly three decisions. A null
     * result means the action is not a permission decision.
     */
    fun permissionDecision(action: ChatAction): PermissionDecision? = when (action) {
        is ChatAction.AllowOnce -> PermissionDecision(allow = true, forSession = false)
        is ChatAction.AllowForSession -> PermissionDecision(allow = true, forSession = true)
        is ChatAction.DenyPermission -> PermissionDecision(allow = false, forSession = false)
        else -> null
    }
}
