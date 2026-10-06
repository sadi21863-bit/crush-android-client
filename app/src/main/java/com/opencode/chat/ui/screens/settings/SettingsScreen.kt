package com.opencode.chat.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opencode.chat.OpenCodeChatApp
import com.opencode.chat.domain.model.AppSettings
import com.opencode.chat.domain.model.PermissionMode
import com.opencode.chat.domain.model.TuningPolicy
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Agent permissions: whether it must ask before acting.
 *
 * This was the app's least defensible default and the user could not change it.
 * PermissionMode defaults to ASK, but nothing ever WROTE the setting - the
 * datastore function existed with zero callers and no row existed in this
 * screen - so ASK was enforced only because it was the hardcoded fallback, and
 * AUTO_APPROVE was unreachable. An agent that edits files deserves a control
 * the user can actually reach, including the choice to stop asking.
 *
 * Deliberately not a chip group like the tuning rows: those pick a value from
 * fixed options, this is one boolean whose OFF state is the safe one, and a
 * switch that starts OFF cannot be knocked into AUTO_APPROVE by a mis-tap.
 */
@Composable
private fun PermissionSection(
    settings: AppSettings,
    onChange: (PermissionMode) -> Unit
) {
    val auto = settings.permissionMode == PermissionMode.AUTO_APPROVE

    Spacer(modifier = Modifier.height(16.dp))
    Text(
        "Permissions",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Auto-approve agent actions",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        if (auto) "AUTO-APPROVE" else "ASK EACH TIME",
                        style = MaterialTheme.typography.labelSmall,
                        // The unsafe state is the one that gets the loud colour.
                        color = if (auto) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    )
                }
                Switch(
                    checked = auto,
                    onCheckedChange = { onChange(if (it) PermissionMode.AUTO_APPROVE else PermissionMode.ASK) }
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                if (auto) {
                    "The agent acts on its own. It will read, change and delete files " +
                        "and run commands in its workspace without asking you first."
                } else {
                    "The agent stops and asks before it reads, changes or deletes files " +
                        "or runs a command."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (auto) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Only turn this on for a task you trust. Blast radius is limited to " +
                        "this app's private storage, but nothing else is asking first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Takes effect the next time the agent starts a session.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Performance tuning. Every row shows its resolved value and whether it came
 * from auto-detection or the user. "AUTO" means the user has NOT overridden it,
 * so the app stays free to re-pick as the device profile changes.
 */
@Composable
private fun TuningSection(
    settings: AppSettings,
    repo: com.opencode.chat.data.repository.SettingsRepository,
    scope: kotlinx.coroutines.CoroutineScope
) {
    val resolved = remember(settings) { repo.resolvedTuning(settings) }
    val profile = remember { repo.deviceProfile }
    val o = settings.tuning

    Spacer(modifier = Modifier.height(16.dp))
    Text(
        "Performance",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                buildString {
                    append("${profile.perfClass.name} Â· API ${profile.apiLevel} Â· ")
                    append("${profile.totalRamMb}MB RAM Â· ${profile.cores} cores")
                    if (profile.isLowRamDevice) append(" Â· low-RAM")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            TuningRow(
                label = "Engine idle timeout",
                help = "How long the engine stays warm after you stop chatting. " +
                    "Lower saves battery and RAM; higher avoids a cold start.",
                value = resolved.engineIdleTimeoutSec,
                isAuto = resolved.sourceOf("idleTimeout") == TuningPolicy.Source.AUTO,
                options = listOf(300 to "5 min", 900 to "15 min", 1800 to "30 min", 3600 to "1 hour"),
                onPick = { sec -> scope.launch { repo.saveTuningOverrides(o.copy(engineIdleTimeoutSec = sec)) } }
            )

            TuningRow(
                label = "Hang detection grace",
                help = "How long the engine may stop responding before it is restarted.",
                value = resolved.hangGraceSec,
                isAuto = resolved.sourceOf("hangGrace") == TuningPolicy.Source.AUTO,
                options = listOf(30 to "30s", 45 to "45s", 60 to "1 min", 120 to "2 min"),
                onPick = { sec -> scope.launch { repo.saveTuningOverrides(o.copy(hangGraceSec = sec)) } }
            )

            TuningRow(
                label = "Prewarm engine on launch",
                help = "Start the engine as soon as the app opens. Faster first " +
                    "message, but costs RAM and battery on weaker devices.",
                value = if (resolved.prewarmEngine) 1 else 0,
                isAuto = resolved.sourceOf("prewarm") == TuningPolicy.Source.AUTO,
                options = listOf(1 to "On", 0 to "Off"),
                onPick = { on -> scope.launch { repo.saveTuningOverrides(o.copy(prewarmEngine = on == 1)) } }
            )

            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = { scope.launch { repo.saveTuningOverrides(TuningPolicy.Overrides()) } }) {
                Text("Reset all to Auto")
            }
        }
    }
}

@Composable
private fun TuningRow(
    label: String,
    help: String,
    value: Int,
    isAuto: Boolean,
    options: List<Pair<Int, String>>,
    onPick: (Int) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                if (isAuto) "AUTO" else "MANUAL",
                style = MaterialTheme.typography.labelSmall,
                color = if (isAuto) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.tertiary
            )
        }
        Text(
            help,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 4.dp)
        ) {
            options.forEach { (opt, text) ->
                FilterChip(
                    selected = value == opt,
                    onClick = { onPick(opt) },
                    label = { Text(text) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit = {}
) {
    val context = LocalContext.current
    val app = context.applicationContext as OpenCodeChatApp
    val viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(app.container.settingsRepository)
    )

    val uiState by viewModel.uiState.collectAsState()
    val settings = uiState.settings

// The stored key is NEVER loaded into this field. The previous version seeded
    // the text field with the decrypted key and offered an eye toggle to reveal
    // it, which put the plaintext secret into Compose state and rendered it on
    // demand. Showing "saved" plus a masked tail conveys everything the eye
    // button did, without the secret ever being present.
val stored = settings.hasApiKey
    var replacing by remember { mutableStateOf(false) }
    var newKey by remember { mutableStateOf("") }

    // SettingsViewModel has been setting uiState.error on a failed Keystore
    // write since it was written, and nothing ever rendered it: the save failed
    // silently and the user was told nothing. A SnackbarHost in the Scaffold
    // makes the failure visible and auto-dismisses, so an old error cannot sit
    // on screen pretending to be current.
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    LaunchedEffect(uiState.error) {
        val msg = uiState.error ?: return@LaunchedEffect
        snackbarScope.launch { snackbarHostState.showSnackbar(msg) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "API",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (!replacing) {
                        if (stored) {
                            Text(
                                "API key saved",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            if (settings.apiKeyHint.isNotBlank()) {
                                Text(
                                    settings.apiKeyHint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            Text(
                                "No API key stored",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                "Add an OpenCode Zen key to start chatting.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        OutlinedTextField(
                            value = newKey,
                            onValueChange = { newKey = it },
                            label = { Text("New OpenCode Zen API Key") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!replacing) {
                            Button(
                                onClick = {
                                    replacing = true
                                    newKey = ""
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (stored) "Replace key" else "Add key")
                            }
                            if (stored) {
                                OutlinedButton(
                                    onClick = {
                                    viewModel.dismissError()
                                    viewModel.clearApiKey()
                                },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Delete")
                                }
                            }
                        } else {
                            Button(
                                onClick = {
                                    // Clear the previous attempt's error before
                                    // starting a new one. Without this a stale
                                    // failure lingers after a later SUCCESSFUL
                                    // save, and an identical repeat failure sets
                                    // the same string twice, which would not
                                    // re-trigger the LaunchedEffect above.
                                    viewModel.dismissError()
                                    viewModel.saveApiKey(newKey.trim())
                                    replacing = false
                                    newKey = ""
                                },
                                modifier = Modifier.weight(1f),
                                enabled = newKey.isNotBlank()
                            ) {
                                Text("Save")
                            }
                            OutlinedButton(
                                onClick = {
                                    replacing = false
                                    newKey = ""
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Cancel")
                            }
                        }
                    }
                }
            }

            Text(
                "Appearance",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Theme", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = settings.themeMode == "system",
                            onClick = { viewModel.saveThemeMode("system") },
                            label = { Text("System") }
                        )
                        FilterChip(
                            selected = settings.themeMode == "light",
                            onClick = { viewModel.saveThemeMode("light") },
                            label = { Text("Light") }
                        )
                        FilterChip(
                            selected = settings.themeMode == "dark",
                            onClick = { viewModel.saveThemeMode("dark") },
                            label = { Text("Dark") }
                        )
                    }
                }
            }

// The "Generation" section - and with it the temperature slider - was
            // REMOVED rather than wired up, and the whole header went with it.
            //
            // The slider persisted to AppSettings.temperature on every drag frame
            // and then nothing read it: SelectedModel carries model/provider/
            // reasoningEffort/maxTokens and no temperature, and neither does
            // AgentMessageRequest. So it advertised "Lower is more focused,
            // higher is more creative" while changing nothing at all - strictly
            // worse than having no control, because the user believes they tuned
            // their output.
            //
            // Re-adding it means either inventing a temperature field on a request
            // DTO (needs an engine capability we have never verified against the
            // live Crush surface) or keeping the lie. A dead control is the one
            // option that needed no verification and shipped anyway.
            //
            // AppSettings.temperature and SettingsRepository.saveTemperature are
            // deliberately left in place: they are persisted state, not UI, and
            // ripping them out of data\ is a larger change than this fix needs.

            // Before the technical tuning block: this is a core behaviour
            // choice, not a device knob.
            PermissionSection(
                settings = settings,
                onChange = { viewModel.savePermissionMode(it) }
            )

            TuningSection(settings, viewModel.repo, viewModel.scope)

            Text(
                "About",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("OpenCode Chat", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Version 1.0.0",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Powered by OpenCode Zen",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    // The only way a user can report a problem. The release build
                    // is non-debuggable, so logcat and `run-as` are both
                    // unavailable to them; this puts the evidence on the clipboard
                    // so it can be pasted into any chat app.
                    OutlinedButton(
                        onClick = onOpenDiagnostics,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Report a problem")
                    }
                }
            }
        }
    }
}

