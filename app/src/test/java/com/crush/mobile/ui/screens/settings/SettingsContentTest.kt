package com.crush.mobile.ui.screens.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.crush.mobile.domain.model.AppSettings
import com.crush.mobile.domain.model.DeviceProfile
import com.crush.mobile.domain.model.PermissionMode
import com.crush.mobile.domain.model.TuningPolicy
import com.crush.mobile.domain.provider.Provider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 2b.4 (V2 plan §6.5): the Settings content layer renders from a
 * fabricated [SettingsViewState] and host callbacks only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h1600dp")
class SettingsContentTest {

    @get:Rule
    val compose = createComposeRule()

    private val zen = Provider(
        id = "opencode-zen",
        displayName = "OpenCode Zen",
        isKnown = true,
        keyHint = "opencode.ai/zen",
    )
    private val other = Provider(
        id = "other",
        displayName = "Other Provider",
        isKnown = false,
        keyHint = "other.example/keys",
    )

    private val tuning = TuningPolicy.Resolved(
        engineIdleTimeoutSec = 900,
        hangGraceSec = 60,
        healthIntervalSec = 10,
        prewarmEngine = false,
        maxRecentSessions = 100,
        source = mapOf(
            "idleTimeout" to TuningPolicy.Source.AUTO,
            "prewarm" to TuningPolicy.Source.USER,
        ),
    )

    private val profile = DeviceProfile(
        apiLevel = 29,
        totalRamMb = 6144,
        availableRamMb = 2048,
        cores = 8,
        isLowRamDevice = false,
        hasStrongBox = false,
        hasSecureLockScreen = true,
        supportsModernAuthGate = true,
    )

    private fun state(
        settings: AppSettings = AppSettings(),
        vaultIds: Set<String> = emptySet(),
        error: String? = null,
    ) = SettingsViewState(
        settings = settings,
        providers = listOf(zen, other),
        selectedProvider = zen,
        vaultIds = vaultIds,
        error = error,
        tuning = tuning,
        profile = profile,
    )

    private fun setContent(
        state: SettingsViewState = state(),
        onBack: () -> Unit = {},
        onOpenDiagnostics: () -> Unit = {},
        onSelectProvider: (String) -> Unit = {},
        onSaveKey: (String) -> Unit = {},
        onClearKey: () -> Unit = {},
        onSelectTheme: (String) -> Unit = {},
        onSelectEnterSend: (String) -> Unit = {},
        onPermissionChange: (PermissionMode) -> Unit = {},
        onTuningChange: (TuningPolicy.Overrides) -> Unit = {},
        onResetTuning: () -> Unit = {},
    ) {
        compose.setContent {
            SettingsContent(
                state = state,
                onBack = onBack,
                onOpenDiagnostics = onOpenDiagnostics,
                onSelectProvider = onSelectProvider,
                onSaveKey = onSaveKey,
                onClearKey = onClearKey,
                onSelectTheme = onSelectTheme,
                onSelectEnterSend = onSelectEnterSend,
                onPermissionChange = onPermissionChange,
                onTuningChange = onTuningChange,
                onResetTuning = onResetTuning,
            )
        }
    }

    @Test
    fun `providers render their names hints and stored-badge state`() {
        setContent(state(vaultIds = setOf(zen.id)))

        compose.onNodeWithText("OpenCode Zen").assertIsDisplayed()
        compose.onNodeWithText("Other Provider").assertIsDisplayed()
        compose.onNodeWithText("unverified on this build", substring = true)
            .assertExists()
        compose.onAllNodesWithText("key stored").assertCountEquals(1)
    }

    @Test
    fun `choosing a provider dispatches its id`() {
        val chosen = mutableListOf<String>()
        setContent(onSelectProvider = { chosen += it })

        compose.onNodeWithText("Other Provider").performClick()
        compose.waitForIdle()

        assertEquals(listOf(other.id), chosen)
    }

    @Test
    fun `a stored key shows the hint and offers replace and delete`() {
        var cleared = 0
        setContent(
            state(
                settings = AppSettings(hasApiKey = true, apiKeyHint = "...abcd"),
            ),
            onClearKey = { cleared += 1 },
        )

        compose.onNodeWithText("API key saved").assertIsDisplayed()
        compose.onNodeWithText("...abcd").assertIsDisplayed()
        compose.onNodeWithText("Replace key").assertIsEnabled()

        compose.onNodeWithText("Delete").performClick()
        compose.waitForIdle()

        assertEquals(1, cleared)
    }

    @Test
    fun `adding a key keeps Save disabled until text and dispatches the trimmed key`() {
        val saved = mutableListOf<String>()
        setContent(onSaveKey = { saved += it })

        compose.onNodeWithText("No API key stored").assertIsDisplayed()
        compose.onNodeWithText("Add key").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("  sk-test  ")
        compose.waitForIdle()
        compose.onNodeWithText("Save").assertIsEnabled().performClick()
        compose.waitForIdle()

        assertEquals(listOf("sk-test"), saved)
    }

    @Test
    fun `theme and keyboard choices dispatch their modes`() {
        val themes = mutableListOf<String>()
        val enterSend = mutableListOf<String>()
        setContent(
            onSelectTheme = { themes += it },
            onSelectEnterSend = { enterSend += it },
        )

        compose.onNodeWithText("Dark").performClick()
        compose.onNodeWithText("Enter sends").performClick()
        compose.waitForIdle()

        assertEquals(listOf("dark"), themes)
        assertEquals(listOf("send"), enterSend)
    }

    @Test
    fun `the permission switch dispatches the auto-approve mode`() {
        val modes = mutableListOf<PermissionMode>()
        setContent(onPermissionChange = { modes += it })

        compose.onNode(isToggleable()).performClick()
        compose.waitForIdle()

        assertEquals(listOf(PermissionMode.AUTO_APPROVE), modes)
    }

    @Test
    fun `tuning shows auto and manual truth and dispatches an override`() {
        val overrides = mutableListOf<TuningPolicy.Overrides>()
        var resets = 0
        setContent(
            onTuningChange = { overrides += it },
            onResetTuning = { resets += 1 },
        )

        compose.onAllNodesWithText("AUTO").assertCountEquals(2)
        compose.onNodeWithText("MANUAL").assertExists()
        compose.onNodeWithText("API 29", substring = true).assertExists()

        // The tuning block sits at the bottom of a long scrolling column; an
        // un-scrolled tap would land outside the viewport and silently do
        // nothing (measured: the reset counter stayed 0 while the click
        // "succeeded" against a clipped node).
        compose.onNodeWithText("5 min").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(300, overrides.single().engineIdleTimeoutSec)

        compose.onNodeWithText("Reset all to Auto").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, resets)
    }

    @Test
    fun `the about card opens diagnostics`() {
        var opens = 0
        setContent(onOpenDiagnostics = { opens += 1 })

        compose.onNodeWithText("Version 1.0.0").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Report a problem").performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(1, opens)
    }

    @Test
    fun `a save error reaches the snackbar`() {
        setContent(state(error = "Could not save the API key."))

        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Could not save the API key.")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `back dispatches the host callback`() {
        var backs = 0
        setContent(onBack = { backs += 1 })

        // The top-bar back control is an icon button, not a text button; the
        // only "Back" here is its content description.
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()

        assertEquals(1, backs)
    }
}
