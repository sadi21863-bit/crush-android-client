package com.opencode.chat.ui.screens.onboarding

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
import com.opencode.chat.OpenCodeChatApp
import com.opencode.chat.data.local.SecureKeyStore
import com.opencode.chat.util.AppLog
import com.opencode.chat.ui.screens.settings.SettingsViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onCompleted: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as OpenCodeChatApp
    val viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(app.container.settingsRepository)
    )

    val uiState by viewModel.uiState.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val secureKeys = remember { SecureKeyStore(ctx) }
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
                "OpenCode Zen",
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

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it.trim() },
                label = { Text("API Key") },
                placeholder = { Text("sk-...") },
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
                    Text("Get your key at opencode.ai")
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
                        val ok = viewModel.saveApiKeyAwait(apiKey)
                        AppLog.i("Onboarding", "saveApiKey ok=$ok")
                        if (ok) onCompleted()
                        else errorText = "Could not save key - please try again"
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
