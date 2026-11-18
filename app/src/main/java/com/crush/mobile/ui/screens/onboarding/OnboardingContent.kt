package com.crush.mobile.ui.screens.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.crush.mobile.domain.provider.Provider

/**
 * Phase 2b.5 (V2 plan §§5.6, 6.5): the onboarding surface, rendered only from
 * [OnboardingViewState] plus callbacks.
 *
 * This composable may not read a Context, a ViewModel, the container, or the
 * Keystore. The host owns the provider selection and the save coroutine; the
 * key draft, the provider menu, the reveal toggle, the saving flag, and the
 * failure text stay local, exactly as they did before the split.
 *
 * [onSubmit] reports its outcome through `onResult` because the write is
 * asynchronous and host-owned: `true` advances via [onCompleted], `false`
 * surfaces the same "Could not save key" line the screen always showed. No
 * validation happens here - that is CORR-001, still deferred.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingContent(
    state: OnboardingViewState,
    onSelectProvider: (String) -> Unit,
    onSubmit: (key: String, onResult: (Boolean) -> Unit) -> Unit,
    onCompleted: () -> Unit,
) {
    val provider = state.selectedProvider
    var providerMenuOpen by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var apiKey by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    // SUBMIT FEEDBACK (skill audit 2026-10-31): the keystore write can take a
    // couple of seconds on a cold device. The button used to sit there looking
    // dead while it ran, so a user who assumes the tap missed re-taps it - the
    // second save is harmless but the impression is not. While saving the
    // button disables, shows a spinner, and says what it is doing; the guard
    // also makes a re-entrant tap impossible.
    var saving by remember { mutableStateOf(false) }

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
            //
            // Dropdown affordance (skill audit 2026-10-31): the button used to
            // read as a static label with no hint it opens a list. A trailing
            // chevron is the convention every Android picker uses, and the
            // merged contentDescription says what the button DOES rather than
            // reciting a bare provider name into the reader out of context.
            OutlinedButton(
                onClick = { providerMenuOpen = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (provider.hasFreeTier) {
                        "${provider.displayName} (free tier)"
                    } else {
                        provider.displayName
                    },
                    modifier = Modifier
                        .weight(1f)
                        // Spoken as one phrase out of context (skill audit:
                        // a bare provider name belongs to the provider list,
                        // not the button that opens it).
                        .semantics {
                            contentDescription =
                                "Provider: ${provider.displayName}" +
                                    if (provider.hasFreeTier) ", free tier" else ""
                        }
                )
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            DropdownMenu(
                expanded = providerMenuOpen,
                onDismissRequest = { providerMenuOpen = false }
            ) {
                // The offered set is Providers.AVAILABLE - providers tested on
                // this build only (2026-11-18). Untested ids remain resolvable
                // in Providers.ALL, but a first-run user is never sent through
                // key setup for an integration nobody has verified.
                state.providers.forEach { p ->
                    DropdownMenuItem(
                        text = {
                            Text(if (p.hasFreeTier) "${p.displayName} (free tier)" else p.displayName)
                        },
                        onClick = {
                            onSelectProvider(p.id)
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
                // Live region: a save failure is announced the moment it lands,
                // once, without the reader having to hunt the screen for it.
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { liveRegion = LiveRegionMode.Polite }
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
                    // gate instead. While saving runs the button is disabled
                    // (enabled below), so no extra re-entry guard is needed.
                    saving = true
                    errorText = null
                    onSubmit(apiKey) { ok ->
                        if (ok) {
                            onCompleted()
                        } else {
                            errorText = "Could not save key - please try again"
                        }
                        saving = false
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                enabled = apiKey.isNotBlank() && !saving
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Saving key...")
                } else {
                    Text("Start chatting")
                }
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
