package com.crush.mobile.ui.screens.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.crush.mobile.domain.model.AppSettings
import com.crush.mobile.domain.model.DeviceProfile
import com.crush.mobile.domain.model.PermissionMode
import com.crush.mobile.domain.model.ReasoningDisplay
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
        onOpenDiagnostics: () -> Unit = {},
        onSelectProvider: (String) -> Unit = {},
        onSaveKey: (String, (Boolean) -> Unit) -> Unit = { _, _ -> },
        onClearKey: () -> Unit = {},
        onSelectTheme: (String) -> Unit = {},
        onSelectEnterSend: (String) -> Unit = {},
        onReasoningDisplayChange: (ReasoningDisplay) -> Unit = {},
        onPermissionChange: (PermissionMode) -> Unit = {},
        onTuningChange: (TuningPolicy.Overrides) -> Unit = {},
        onResetTuning: () -> Unit = {},
    ) {
        compose.setContent {
            SettingsContent(
                state = state,
                onOpenDiagnostics = onOpenDiagnostics,
                onSelectProvider = onSelectProvider,
                onSaveKey = onSaveKey,
                onClearKey = onClearKey,
                onSelectTheme = onSelectTheme,
                onSelectEnterSend = onSelectEnterSend,
                onReasoningDisplayChange = onReasoningDisplayChange,
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

        // A11: Delete asks first; the confirmation is now part of this path.
        compose.onNodeWithTag("delete-key").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("confirm-delete-key").performClick()
        compose.waitForIdle()

        assertEquals(1, cleared)
    }

    @Test
    fun `delete asks for confirmation naming the provider and dismiss does nothing`() {
        var cleared = 0
        setContent(
            state(settings = AppSettings(hasApiKey = true, apiKeyHint = "...abcd")),
            onClearKey = { cleared += 1 },
        )

        compose.onNodeWithTag("delete-key").performClick()
        compose.waitForIdle()

        // The tap only opened the dialog - nothing is deleted yet.
        assertEquals(0, cleared)
        compose.onNodeWithText("Delete saved key?").assertExists()
        compose.onNodeWithText(
            "Delete the saved key for OpenCode Zen? " +
                "You will need to enter it again before sending messages."
        ).assertExists()

        // Dismissing must leave the vault untouched.
        compose.onNodeWithTag("cancel-delete-key").performClick()
        compose.waitForIdle()
        assertEquals(0, cleared)
        compose.onNodeWithText("Delete saved key?").assertDoesNotExist()

        // Only the dialog's Delete performs the clear.
        compose.onNodeWithTag("delete-key").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("confirm-delete-key").performClick()
        compose.waitForIdle()
        assertEquals(1, cleared)
    }

    @Test
    fun `adding a key keeps Save disabled until text and dispatches the trimmed key`() {
        val saved = mutableListOf<String>()
        setContent(onSaveKey = { key, _ -> saved += key })

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
    fun `save keeps the editor and draft until the result callback succeeds`() {
        val saved = mutableListOf<String>()
        var pending: ((Boolean) -> Unit)? = null
        setContent(onSaveKey = { key, onResult ->
            saved += key
            pending = onResult
        })

        compose.onNodeWithText("Add key").performClick()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performTextInput("sk-live")
        compose.waitForIdle()
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()

        // The tap dispatched the key but closed nothing: the write is still in
        // flight, so the editor must stay open with Save disabled (A10).
        assertEquals(listOf("sk-live"), saved)
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        compose.onNodeWithText("Save").assertIsNotEnabled()

        // Success is the only thing that closes the editor...
        compose.runOnIdle { pending!!.invoke(true) }
        compose.waitForIdle()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText("No API key stored").assertIsDisplayed()

        // ...and clears the draft: a fresh editor has nothing to save.
        compose.onNodeWithText("Add key").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun `a failed save leaves the draft and the editor in place`() {
        var pending: ((Boolean) -> Unit)? = null
        setContent(onSaveKey = { _, onResult -> pending = onResult })

        compose.onNodeWithText("Add key").performClick()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performTextInput("sk-keep")
        compose.waitForIdle()
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()

        compose.runOnIdle { pending!!.invoke(false) }
        compose.waitForIdle()

        // Editor still open, draft still there: Save enabled means newKey is
        // still non-blank (the field is password-transformed, so the text
        // itself is not readable from semantics).
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        compose.onNodeWithText("Save").assertIsEnabled()
        compose.onNodeWithText("Cancel").assertIsEnabled()
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

        // The ON direction is now gated behind a confirmation.
        compose.onNodeWithTag("auto-approve-switch").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("confirm-auto-approve").performClick()
        compose.waitForIdle()

        assertEquals(listOf(PermissionMode.AUTO_APPROVE), modes)
    }

    @Test
    fun `auto-approve asks first names the consequences and cancels to ASK`() {
        val modes = mutableListOf<PermissionMode>()
        setContent(onPermissionChange = { modes += it })

        compose.onNodeWithTag("auto-approve-switch").performClick()
        compose.waitForIdle()

        // Nothing is written by the tap that opens the dialog, and the switch
        // still renders ASK because its value comes from settings.
        assertEquals(0, modes.size)
        compose.onNodeWithTag("auto-approve-switch").assertIsOff()
        compose.onNodeWithText("Turn on auto-approve?").assertExists()
        compose.onNodeWithText("read, change and delete files", substring = true)
            .assertExists()
        compose.onNodeWithText("commands in its workspace", substring = true)
            .assertExists()

        // Cancelling leaves ASK in place and writes nothing.
        compose.onNodeWithTag("cancel-auto-approve").performClick()
        compose.waitForIdle()
        assertEquals(0, modes.size)
        compose.onNodeWithTag("auto-approve-switch").assertIsOff()
        compose.onNodeWithText("Turn on auto-approve?").assertDoesNotExist()

        // Confirming is the only path to AUTO_APPROVE.
        compose.onNodeWithTag("auto-approve-switch").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("confirm-auto-approve").performClick()
        compose.waitForIdle()
        assertEquals(listOf(PermissionMode.AUTO_APPROVE), modes)
    }

    @Test
    fun `turning auto-approve off is immediate and needs no dialog`() {
        val modes = mutableListOf<PermissionMode>()
        setContent(
            state(settings = AppSettings(permissionMode = PermissionMode.AUTO_APPROVE)),
            onPermissionChange = { modes += it },
        )

        compose.onNodeWithTag("auto-approve-switch").performClick()
        compose.waitForIdle()

        assertEquals(listOf(PermissionMode.ASK), modes)
        compose.onNodeWithText("Turn on auto-approve?").assertDoesNotExist()
    }

    @Test
    fun `the reasoning choice dispatches the off mode`() {
        val values = mutableListOf<ReasoningDisplay>()
        setContent(onReasoningDisplayChange = { values += it })

        compose.onNodeWithTag("reasoning-off").performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(listOf(ReasoningDisplay.OFF), values)
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
    fun `the missing-key line names the provider and uses a valid article`() {
        setContent()

        // Finding 13: "Add a OpenCode Zen key" was ungrammatical and read as
        // if the provider were the object of "add".
        compose.onNodeWithText("Add a key for OpenCode Zen to start chatting.")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Add a OpenCode", substring = true).assertDoesNotExist()
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
    fun `a top-level destination has no back control`() {
        setContent()

        // V2-3: Settings is a primary destination reached from the shell, so
        // the screen must not own a back arrow. Back is the system/back-stack
        // contract (Chat stays at the root).
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
    }
}
