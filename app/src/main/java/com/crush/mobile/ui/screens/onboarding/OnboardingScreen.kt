package com.crush.mobile.ui.screens.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crush.mobile.CrushMobileApp
import com.crush.mobile.data.local.SecureKeyStore
import com.crush.mobile.domain.provider.Providers
import com.crush.mobile.ui.screens.settings.SettingsViewModel
import kotlinx.coroutines.launch

/**
 * The onboarding surface's HOST (Phase 2b.5 extraction).
 *
 * It owns the ViewModel, the provider selection, and the save coroutine; the
 * render tree lives in [OnboardingContent], which receives an
 * [OnboardingViewState] and callbacks only. The public screen signature is
 * unchanged.
 */
@Composable
fun OnboardingScreen(
    onCompleted: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as CrushMobileApp
    val viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(app.container.settingsRepository)
    )
    val scope = rememberCoroutineScope()
    // Onboarding is the FIRST place a key is stored, so the user picks the
    // provider HERE too. The key must be saved under the provider it belongs
    // to: the first build saved every onboarding key under the default
    // provider's scope, so a Groq key pasted at onboarding left the app
    // reading "no key for opencode-zen" in chat while Settings showed a
    // perfectly saved Groq key that nothing would use.
    var providerId by remember { mutableStateOf(SecureKeyStore.LEGACY_PROVIDER) }
    val provider = remember(providerId) {
        Providers.byId(providerId) ?: Providers.DEFAULT
    }

    OnboardingContent(
        state = OnboardingViewState(
            // Tested providers only: an untested row would take a first-run user
            // through key setup before discovering it does not work.
            providers = Providers.AVAILABLE,
            selectedProvider = provider,
        ),
        onSelectProvider = { providerId = it },
        onSubmit = { key, onResult ->
            scope.launch {
                val ok = viewModel.saveApiKeyAwait(key, providerId)
                // Persist the choice only after the key landed: the chat
                // screen reads the persisted provider on its first
                // bootstrap to decide which key scope to read.
                if (ok) viewModel.saveProviderId(providerId)
                onResult(ok)
            }
        },
        onCompleted = onCompleted,
    )
}
