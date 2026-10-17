package com.crush.mobile.ui.screens.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crush.mobile.CrushMobileApp
import com.crush.mobile.data.local.SecureKeyStore
import com.crush.mobile.domain.provider.Provider
import com.crush.mobile.domain.provider.Providers
import com.crush.mobile.ui.screens.settings.SettingsViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onCompleted: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as CrushMobileApp
    val viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(app.container.settingsRepository)
    )

    val uiState by viewModel.uiState.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val secureKeys = remember { SecureKeyStore(ctx) }
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
    var providerMenuOpen by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var apiKey by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                modifier = Modifier.size(80.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                "Crush Mobile",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                "Chat with 70+ models from a single API key",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Provider selector. Plain DropdownMenu, not ExposedDropdownMenuBox:
            // the box's menuAnchor() API has churned across material3 versions,
            // and the plain popup needs no anchor plumbing at all.
            OutlinedButton(
                onClick = { providerMenuOpen = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (provider.hasFreeTier) {
                        "${provider.displayName} (free tier)"
                    } else {
                        provider.displayName
                    }
                )
            }
            DropdownMenu(
                expanded = providerMenuOpen,
                onDismissRequest = { providerMenuOpen = false }
            ) {
                Providers.ALL.forEach { p ->
                    DropdownMenuItem(
                        text = {
                            Text(if (p.hasFreeTier) "${p.displayName} (free tier)" else p.displayName)
                        },
                        onClick = {
                            providerId = p.id
                            providerMenuOpen = false
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it.trim() },
                label = { Text("API Key") },
                placeholder = { Text(keyPlaceholder(provider)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (showKey) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(
                            if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showKey) "Hide" else "Show"
                        )
                    }
                },
                supportingText = {
                    Text("Get your key at ${provider.keyHint}")
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            errorText?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            Button(
                onClick = {
                    // No biometric prompt here any more.
                    //
                    // The key used to be auth-GATED, so saving it required the
                    // user to authenticate first, and that prompt is what
                    // crashed onboarding on an API 30+ device (androidx.biometric
                    // needs an AppCompat theme this app did not have). The key is
                    // now encrypted but not auth-gated, so saving is a plain
                    // write and the app lock at Chat entry is the user-facing
                    // gate instead.
                    scope.launch {
                        val ok = viewModel.saveApiKeyAwait(apiKey, providerId)
                        // Persist the choice only after the key landed: the chat
                        // screen reads the persisted provider on its first
                        // bootstrap to decide which key scope to read.
                        if (ok) {
                            viewModel.saveProviderId(providerId)
                            onCompleted()
                        } else {
                            errorText = "Could not save key - please try again"
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                enabled = apiKey.isNotBlank()
            ) {
                Text("Start chatting")
            }
        }
    }
}

/**
 * What a key for this provider looks like, shown as the field's hint. Real key
 * shapes differ (`gsk_...` for Groq, `sk-hyper-...` for Hyper), and a hardcoded
 * "sk-..." made every provider look like OpenAI.
 */
private fun keyPlaceholder(provider: Provider): String = when {
    provider.id.startsWith("opencode") -> "oc_sk_..."
    provider.id == "groq" -> "gsk_..."
    provider.id == "hyper" -> "sk-hyper-..."
    provider.id == "openrouter" -> "sk-or-..."
    provider.id == "copilot" -> "OAuth sign-in only"
    else -> "sk-..."
}
