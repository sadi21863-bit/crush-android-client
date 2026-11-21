@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.crush.mobile.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.crush.mobile.domain.model.AppSettings
import com.crush.mobile.domain.model.DeviceProfile
import com.crush.mobile.domain.model.PermissionMode
import com.crush.mobile.domain.model.TuningPolicy
import com.crush.mobile.ui.components.CrushButton
import com.crush.mobile.ui.components.CrushButtonVariant
import com.crush.mobile.ui.components.CrushCard
import com.crush.mobile.ui.components.CrushChoiceChip
import com.crush.mobile.ui.components.CrushSectionHeader
import com.crush.mobile.ui.components.CrushStatusLabel
import com.crush.mobile.ui.components.CrushTone
import com.crush.mobile.ui.theme.CrushShape
import com.crush.mobile.ui.theme.CrushSpacing
import com.crush.mobile.ui.theme.CrushType
import com.crush.mobile.ui.theme.LocalCrushColors
import kotlinx.coroutines.launch

/**
 * Phase 2b.4 (V2 plan §§5.6, 6.5), restyled by V2-6: the Settings surface,
 * rendered only from [SettingsViewState] plus callbacks.
 *
 * This composable may not read a Context, a ViewModel, the container, the
 * settings repository, a coroutine scope, or the data store. The host resolves
 * the provider list, the selected provider, the device profile, and the tuning
 * values; key/provider/theme writes cross back as callbacks. The key field and
 * the provider's "key stored" badge work from `hasApiKey`/`vaultIds` only -
 * plaintext never enters this layer.
 *
 * V2-6-D2 puts the sections in the brief's order: provider/credential ->
 * permissions -> appearance -> performance -> about/diagnostics.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    state: SettingsViewState,
    onOpenDiagnostics: () -> Unit,
    onSelectProvider: (String) -> Unit,
    onSaveKey: (String) -> Unit,
    onClearKey: () -> Unit,
    onSelectTheme: (String) -> Unit,
    onSelectEnterSend: (String) -> Unit,
    onPermissionChange: (PermissionMode) -> Unit,
    onTuningChange: (TuningPolicy.Overrides) -> Unit,
    onResetTuning: () -> Unit,
) {
    val settings = state.settings
    val provider = state.selectedProvider
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
    LaunchedEffect(state.error) {
        val msg = state.error ?: return@LaunchedEffect
        snackbarScope.launch { snackbarHostState.showSnackbar(msg) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Settings") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = CrushSpacing.lg)
                .padding(top = CrushSpacing.md, bottom = CrushSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(CrushSpacing.lg)
        ) {
            CrushSectionHeader("Provider")

            // The provider is chosen BEFORE the key, because the key belongs to a
            // provider: a Zen key pasted while OpenCode Go is selected is stored
            // fine and then fails every turn. Showing them as one "API" block
            // encouraged exactly that.
            CrushCard {
                // The OFFER LIST (2026-11-18): only providers tested on this
                // build, plus any slot already in the vault - a configured
                // credential must stay selectable. The full curated table
                // remains in Providers.ALL for resolution, so hiding a row never
                // strands a persisted setting. There is still no silent default:
                // the resolver falls back to Zen for boot continuity only, and
                // the user picks explicitly.
                state.providers.forEach { p ->
                    val selected = p.id == provider.id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectProvider(p.id) }
                            .padding(vertical = CrushSpacing.sm, horizontal = CrushSpacing.xs),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selected, onClick = { onSelectProvider(p.id) })
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                p.displayName,
                                style = CrushType.body,
                                color = LocalCrushColors.current.ink
                            )
                            Text(
                                buildString {
                                    append("key from ").append(p.keyHint)
                                    if (!p.isKnown) {
                                        append(" - unverified on this build")
                                    }
                                },
                                style = CrushType.secondary,
                                color = LocalCrushColors.current.muted
                            )
                            // Per-provider vault badge (2026-11-01). The vault
                            // holds one credential per provider, but until now
                            // only the ACTIVE one's saved state was visible
                            // ("API key saved" below), so the user could not
                            // know a switch would actually work. Slot presence
                            // only: this never implies the plaintext is shown
                            // here. It sits on its own line (V2-6 2.0x check):
                            // beside the name it stole the column width and
                            // wrapped the hint word-by-word at 2.0x.
                            if (p.id in state.vaultIds) {
                                Spacer(Modifier.height(CrushSpacing.xs))
                                CrushStatusLabel(
                                    text = "key stored",
                                    tone = CrushTone.Success,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(CrushSpacing.sm))
                Text(
                    "Changing provider re-runs the whole handshake and clears the " +
                        "model choice: a model id from one provider's catalogue is " +
                        "meaningless to another.",
                    style = CrushType.secondary,
                    color = LocalCrushColors.current.muted
                )
            }

            CrushSectionHeader("API key")

            CrushCard {
                if (!replacing) {
                    if (stored) {
                        Text(
                            "API key saved",
                            style = CrushType.body,
                            color = LocalCrushColors.current.ink
                        )
                        if (settings.apiKeyHint.isNotBlank()) {
                            Text(
                                settings.apiKeyHint,
                                style = CrushType.secondary,
                                color = LocalCrushColors.current.muted
                            )
                        }
                    } else {
                        Text(
                            "No API key stored",
                            style = CrushType.body,
                            color = LocalCrushColors.current.ink
                        )
                        // Finding 13: "Add a OpenCode Zen key" was ungrammatical
                        // for every provider whose name starts with a vowel, and
                        // read as if the provider were the object of "add". This
                        // wording works for every name.
                        Text(
                            "Add a key for ${provider.displayName} to start chatting.",
                            style = CrushType.body,
                            color = LocalCrushColors.current.muted
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = newKey,
                        onValueChange = { newKey = it },
                        label = { Text("New ${provider.displayName} API Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = CrushShape.small,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                }

                Spacer(modifier = Modifier.height(CrushSpacing.md))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(CrushSpacing.sm)
                ) {
                    if (!replacing) {
                        CrushButton(
                            text = if (stored) "Replace key" else "Add key",
                            onClick = {
                                replacing = true
                                newKey = ""
                            },
                            modifier = Modifier.weight(1f),
                            variant = CrushButtonVariant.Primary,
                        )
                        if (stored) {
                            CrushButton(
                                text = "Delete",
                                onClick = onClearKey,
                                modifier = Modifier.weight(1f),
                                variant = CrushButtonVariant.Secondary,
                            )
                        }
                    } else {
                        CrushButton(
                            text = "Save",
                            onClick = {
                                // The host clears the previous attempt's error
                                // before starting a new one, so a stale failure
                                // cannot linger after a later SUCCESSFUL save.
                                onSaveKey(newKey.trim())
                                replacing = false
                                newKey = ""
                            },
                            modifier = Modifier.weight(1f),
                            enabled = newKey.isNotBlank(),
                            variant = CrushButtonVariant.Primary,
                        )
                        CrushButton(
                            text = "Cancel",
                            onClick = {
                                replacing = false
                                newKey = ""
                            },
                            modifier = Modifier.weight(1f),
                            variant = CrushButtonVariant.Secondary,
                        )
                    }
                }
            }

            PermissionSection(
                settings = settings,
                onChange = onPermissionChange
            )

            CrushSectionHeader("Appearance")

            CrushCard {
                Text(
                    "Theme",
                    style = CrushType.body,
                    color = LocalCrushColors.current.ink
                )
                Spacer(modifier = Modifier.height(CrushSpacing.sm))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(CrushSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(CrushSpacing.sm)
                ) {
                    CrushChoiceChip(
                        text = "System",
                        selected = settings.themeMode == "system",
                        onClick = { onSelectTheme("system") },
                    )
                    CrushChoiceChip(
                        text = "Light",
                        selected = settings.themeMode == "light",
                        onClick = { onSelectTheme("light") },
                    )
                    CrushChoiceChip(
                        text = "Dark",
                        selected = settings.themeMode == "dark",
                        onClick = { onSelectTheme("dark") },
                    )
                }
            }

            CrushCard {
                Text(
                    "Keyboard",
                    style = CrushType.body,
                    color = LocalCrushColors.current.ink
                )
                Spacer(modifier = Modifier.height(CrushSpacing.sm))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(CrushSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(CrushSpacing.sm)
                ) {
                    CrushChoiceChip(
                        text = "Enter sends",
                        selected = settings.enterSendMode == "send",
                        onClick = { onSelectEnterSend("send") },
                    )
                    CrushChoiceChip(
                        text = "Enter = newline",
                        selected = settings.enterSendMode == "newline",
                        onClick = { onSelectEnterSend("newline") },
                    )
                }
                Spacer(Modifier.height(CrushSpacing.xs))
                Text(
                    "In \"Enter sends\" the keyboard action button sends the " +
                        "message; the key stops inserting newlines. Switch back " +
                        "to write multi-line drafts.",
                    style = CrushType.secondary,
                    color = LocalCrushColors.current.muted
                )
            }

            // The "Generation" section - and with it the temperature slider -
            // was REMOVED rather than wired up, and the whole header went with
            // it. AppSettings.temperature and saveTemperature stay in data,
            // deliberately: they are persisted state, not UI.

            TuningSection(
                settings = settings,
                resolved = state.tuning,
                profile = state.profile,
                onTuningChange = onTuningChange,
                onReset = onResetTuning,
            )

            CrushSectionHeader("About")

            CrushCard {
                Text(
                    "Crush Mobile",
                    style = CrushType.section,
                    color = LocalCrushColors.current.ink
                )
                Spacer(Modifier.height(CrushSpacing.xs))
                Text(
                    "Version 1.0.0",
                    style = CrushType.secondary,
                    color = LocalCrushColors.current.muted
                )
                Spacer(Modifier.height(CrushSpacing.xs))
                // This line used to say "Powered by OpenCode Zen" - a
                // PROVIDER claim that was wrong for every other provider
                // (fourth Zen-hardcode, found in the 2026-11-02 audit
                // request). What this app is powered by is the ENGINE,
                // which stays the same regardless of which provider the
                // user keys; attribution lives in NOTICE.md.
                Text(
                    "Powered by the bundled Crush engine (see NOTICE)",
                    style = CrushType.secondary,
                    color = LocalCrushColors.current.muted
                )
                Spacer(Modifier.height(CrushSpacing.md))
                // The only way a user can report a problem. The release build
                // is non-debuggable, so logcat and `run-as` are both
                // unavailable to them; this puts the evidence on the clipboard
                // so it can be pasted into any chat app.
                CrushButton(
                    text = "Report a problem",
                    onClick = onOpenDiagnostics,
                    modifier = Modifier.fillMaxWidth(),
                    variant = CrushButtonVariant.Secondary,
                )
            }
        }
    }
}

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
    val colors = LocalCrushColors.current

    CrushSectionHeader("Permissions")
    CrushCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Auto-approve agent actions",
                    style = CrushType.body,
                    color = colors.ink
                )
                Text(
                    if (auto) "AUTO-APPROVE" else "ASK EACH TIME",
                    style = CrushType.metadata,
                    // The unsafe state is the one that gets the loud colour.
                    color = if (auto) colors.error else colors.accent
                )
            }
            Switch(
                checked = auto,
                onCheckedChange = {
                    onChange(
                        if (it) PermissionMode.AUTO_APPROVE else PermissionMode.ASK
                    )
                }
            )
        }
        Spacer(Modifier.height(CrushSpacing.sm))
        Text(
            if (auto) {
                "The agent acts on its own. It will read, change and delete files " +
                    "and run commands in its workspace without asking you first."
            } else {
                "The agent stops and asks before it reads, changes or deletes files " +
                    "or runs a command."
            },
            style = CrushType.body,
            color = colors.muted
        )
        if (auto) {
            Spacer(Modifier.height(CrushSpacing.sm))
            Text(
                "Only turn this on for a task you trust. Blast radius is limited to " +
                    "this app's private storage, but nothing else is asking first.",
                style = CrushType.body,
                color = colors.error
            )
        }
        Spacer(Modifier.height(CrushSpacing.xs))
        Text(
            "Takes effect the next time the agent starts a session.",
            style = CrushType.metadata,
            color = colors.muted
        )
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
    resolved: TuningPolicy.Resolved,
    profile: DeviceProfile,
    onTuningChange: (TuningPolicy.Overrides) -> Unit,
    onReset: () -> Unit,
) {
    val o = settings.tuning
    val colors = LocalCrushColors.current

    CrushSectionHeader("Performance")
    CrushCard {
        Text(
            buildString {
                append("${profile.perfClass.name} · API ${profile.apiLevel} · ")
                append("${profile.totalRamMb}MB RAM · ${profile.cores} cores")
                if (profile.isLowRamDevice) append(" · low-RAM")
            },
            style = CrushType.metadata,
            color = colors.muted
        )
        Spacer(Modifier.height(CrushSpacing.md))

        TuningRow(
            label = "Engine idle timeout",
            help = "How long the engine stays warm after you stop chatting. " +
                "Lower saves battery and RAM; higher avoids a cold start.",
            value = resolved.engineIdleTimeoutSec,
            isAuto = resolved.sourceOf("idleTimeout") == TuningPolicy.Source.AUTO,
            options = listOf(300 to "5 min", 900 to "15 min", 1800 to "30 min", 3600 to "1 hour"),
            onPick = { sec ->
                onTuningChange(o.copy(engineIdleTimeoutSec = sec))
            }
        )

        TuningRow(
            label = "Hang detection grace",
            help = "How long the engine may stop responding before it is restarted.",
            value = resolved.hangGraceSec,
            isAuto = resolved.sourceOf("hangGrace") == TuningPolicy.Source.AUTO,
            options = listOf(30 to "30s", 45 to "45s", 60 to "1 min", 120 to "2 min"),
            onPick = { sec ->
                onTuningChange(o.copy(hangGraceSec = sec))
            }
        )

        TuningRow(
            label = "Prewarm engine on launch",
            help = "Start the engine as soon as the app opens. Faster first " +
                "message, but costs RAM and battery on weaker devices.",
            value = if (resolved.prewarmEngine) 1 else 0,
            isAuto = resolved.sourceOf("prewarm") == TuningPolicy.Source.AUTO,
            options = listOf(1 to "On", 0 to "Off"),
            onPick = { on ->
                onTuningChange(o.copy(prewarmEngine = on == 1))
            }
        )

        Spacer(Modifier.height(CrushSpacing.sm))
        CrushButton(
            text = "Reset all to Auto",
            onClick = onReset,
            variant = CrushButtonVariant.TextOnly,
        )
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
    val colors = LocalCrushColors.current
    Column(modifier = Modifier.padding(vertical = CrushSpacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = CrushType.body,
                color = colors.ink,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (isAuto) "AUTO" else "MANUAL",
                style = CrushType.metadata,
                color = if (isAuto) colors.muted else colors.accent
            )
        }
        Text(
            help,
            style = CrushType.secondary,
            color = colors.muted
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(CrushSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(CrushSpacing.sm),
            modifier = Modifier.padding(top = CrushSpacing.xs)
        ) {
            options.forEach { (opt, text) ->
                CrushChoiceChip(
                    text = text,
                    selected = value == opt,
                    onClick = { onPick(opt) },
                )
            }
        }
    }
}
