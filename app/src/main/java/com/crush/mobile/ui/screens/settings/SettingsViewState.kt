package com.crush.mobile.ui.screens.settings

import com.crush.mobile.domain.model.AppSettings
import com.crush.mobile.domain.model.DeviceProfile
import com.crush.mobile.domain.model.TuningPolicy
import com.crush.mobile.domain.provider.Provider

/**
 * Phase 2b.4 (V2 plan §§5.6, 6.5): the Settings surface's inert view state.
 *
 * The host resolves the device profile, the tuning values, and the selected
 * provider (all Context/repository reads) and passes the resulting domain
 * values here. The content layer reads no repository, ViewModel, Context, or
 * coroutine scope.
 */
data class SettingsViewState(
    val settings: AppSettings,
    val providers: List<Provider>,
    val selectedProvider: Provider,
    val vaultIds: Set<String> = emptySet(),
    val error: String? = null,
    val tuning: TuningPolicy.Resolved,
    val profile: DeviceProfile,
)
