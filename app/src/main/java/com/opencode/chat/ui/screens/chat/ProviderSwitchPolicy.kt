package com.opencode.chat.ui.screens.chat

/**
 * The provider-switch serialisation policy (audit P1-07).
 *
 * WHY THIS EXISTS
 *
 * Switching provider rebuilds the whole engine handshake: it drops the
 * workspace id and the cached key, clears both model slots, stops the shared
 * SSE connection and re-bootstraps. Applied while a turn is streaming, that
 * kills the events the turn is consuming, loses the file-change measurement
 * the turn's finally was about to collect, and drops the key that a lazy
 * `require()` on the same run still resolves to - the reply dies silently
 * while the UI keeps its old bubble.
 *
 * The rule, decided in one place:
 *
 *  - asking for the provider that is already active with nothing deferred is
 *    a no-op (the settings flow re-emits on every preference change, and
 *    rebuilding the workspace for a theme tweak would be absurd);
 *  - asking for the already-active provider while a switch is deferred
 *    CANCELS the deferred switch - the decision is about what the user wants
 *    now, not what they wanted a moment ago;
 *  - anything else while a run is in flight is DEFERRED, not applied: the
 *    persisted setting already says "new", so a relaunch lands there
 *    regardless, and the run's exit path applies the deferred id as soon as
 *    nothing is active.
 */
internal object ProviderSwitchPolicy {

    /** What a persisted provider change should do right now. */
    sealed class Decision {
        /** Already active and nothing deferred: do nothing. */
        object NoOp : Decision()

        /** Already active but a deferred switch existed: cancel the deferral. */
        object ClearPending : Decision()

        /** No turn in flight: apply the new provider now. */
        object ApplyNow : Decision()

        /** A turn is in flight: remember the request until it ends. */
        object Defer : Decision()
    }

    fun resolve(
        current: String,
        requested: String,
        runInFlight: Boolean,
        pending: String?
    ): Decision = when {
        requested == current ->
            if (pending == null) Decision.NoOp else Decision.ClearPending
        runInFlight -> Decision.Defer
        else -> Decision.ApplyNow
    }
}
