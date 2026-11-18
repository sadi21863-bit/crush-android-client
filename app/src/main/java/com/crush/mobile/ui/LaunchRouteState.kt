package com.crush.mobile.ui

/**
 * The app's launch destination: chat when a credential was already stored,
 * onboarding when the selected provider had none.
 *
 * WHY THIS IS PINNED PER PROCESS AND NOT DERIVED PER COMPOSITION
 *
 * Finding 12 (device, 2026-11-18): selecting a provider with no stored key in
 * Settings persisted the id, which flipped `hasStoredCiphertext(selected)`,
 * which changed `NavGraph`'s start destination, which rebuilt the `NavHost` and
 * discarded the Settings back stack - the user was yanked to Onboarding for
 * merely tapping a radio button.
 *
 * The start destination is a LAUNCH decision, not a reaction to a settings
 * write. Deciding once and then navigating explicitly keeps the graph stable;
 * every later route change is something the user actually asked for. It is
 * process-scoped for the same reason [com.crush.mobile.data.local.LockState] is:
 * Android recreates the Activity around credential-screen closure (measured
 * 2026-10-20), and a re-decided route would rebuild the NavHost around a
 * different start destination and throw away the restored stack.
 *
 * A fresh process re-decides - that is exactly when routing to Onboarding for a
 * missing key is the honest answer.
 */
class LaunchRouteState {

    @Volatile
    private var cached: Boolean? = null

    /**
     * True when the app should start in chat.
     *
     * [hasStoredCiphertext] runs ONCE, on the first call. A probe that throws is
     * not memoised - the next call retries rather than pinning a decision made
     * from a failed read.
     */
    fun startWithChat(hasStoredCiphertext: () -> Boolean): Boolean {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: hasStoredCiphertext().also { cached = it }
        }
    }
}
