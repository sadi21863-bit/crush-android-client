package com.crush.mobile.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crush.mobile.CrushMobileApp
import com.crush.mobile.domain.provider.Providers
import kotlinx.coroutines.launch

/**
 * The Settings surface's HOST (Phase 2b.4 extraction).
 *
 * It owns the ViewModel, the repository-derived provider/tuning/device values,
 * and the write callbacks. The render tree lives in [SettingsContent], which
 * receives a [SettingsViewState] and callbacks only. The public screen
 * signature is unchanged.
 */
@Composable
fun SettingsScreen(
    onOpenDiagnostics: () -> Unit = {}
) {
    val context = LocalContext.current
    val app = context.applicationContext as CrushMobileApp
    val viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(app.container.settingsRepository)
    )

    val uiState by viewModel.uiState.collectAsState()
    // A10: the suspend save needs a scope for the duration of one tap. The
    // scope is tied to this composition, so leaving Settings cannot leave a
    // callback pointing at a screen that is gone; the editor state it updates
    // dies with the screen anyway.
    val scope = rememberCoroutineScope()
    val settings = uiState.settings
    val provider = Providers.resolve(settings.providerId)
    val resolvedTuning = remember(settings) {
        viewModel.settingsRepository.resolvedTuning(settings)
    }
    val deviceProfile = remember { viewModel.settingsRepository.deviceProfile }

    SettingsContent(
        state = SettingsViewState(
            settings = settings,
            // Only providers tested on this build (plus any slot already in the
            // vault, so a credential is never stranded behind a hidden row).
            providers = Providers.offerable(uiState.vaultIds + settings.providerId),
            selectedProvider = provider,
            vaultIds = uiState.vaultIds,
            error = uiState.error,
            tuning = resolvedTuning,
            profile = deviceProfile,
        ),
        onOpenDiagnostics = onOpenDiagnostics,
        onSelectProvider = viewModel::saveProviderId,
        onSaveKey = { key, onResult ->
            // Clear the previous attempt's error BEFORE starting a new one.
            // Without this a stale failure lingers after a later SUCCESSFUL
            // save, and an identical repeat failure sets the same string twice,
            // which would not re-trigger the snackbar effect.
            viewModel.dismissError()
            // A10: report the real outcome instead of fire-and-forget, so the
            // content can keep the editor and the draft until the Keystore
            // write actually succeeded.
            scope.launch {
                val ok = viewModel.saveApiKeyAwait(key, provider.id)
                onResult(ok)
            }
        },
        onClearKey = {
            viewModel.dismissError()
            viewModel.clearApiKey(provider.id)
        },
        onSelectTheme = viewModel::saveThemeMode,
        onSelectEnterSend = viewModel::saveEnterSendMode,
        onReasoningDisplayChange = viewModel::saveReasoningDisplay,
        onPermissionChange = viewModel::savePermissionMode,
        onTuningChange = viewModel::saveTuning,
        onResetTuning = viewModel::resetTuning,
    )
}
