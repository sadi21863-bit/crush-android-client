package com.crush.mobile.ui.screens.onboarding

import com.crush.mobile.domain.provider.Provider

/**
 * Phase 2b.5 (V2 plan §§5.6, 6.5): the onboarding surface's inert view state.
 *
 * Only the provider catalogue and the selected row cross the seam. The key
 * draft, the menu/reveal toggles, the saving flag, and the failure text are
 * local view state and stay `remember`-scoped in [OnboardingContent], as they
 * were in the pre-split screen.
 */
data class OnboardingViewState(
    val providers: List<Provider>,
    val selectedProvider: Provider,
)
