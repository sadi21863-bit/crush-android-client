package com.crush.mobile.ui.screens.projects

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.crush.mobile.data.project.PendingProjectTrust
import com.crush.mobile.data.project.TrustVerdict.TrustReason
import com.crush.mobile.project.ProjectRef
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 2b.2 (V2 plan §6.5): the Projects content layer renders from
 * fabricated [ProjectsUiState] and host-provided values only.
 *
 * Recorded scope note: the plan's Projects coverage list names a "current
 * marker", but the as-built surface has none and §4.7.2 assigns that target to
 * the Phase 7 redesign. 2b.2 is an extraction, so this suite pins the actual
 * pixels and interactions and does not invent the marker.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h1600dp")
class ProjectsContentTest {

    @get:Rule
    val compose = createComposeRule()

    // Host-portable absolute paths: the surface compares `File.absolutePath`
    // values, and on the Windows JVM a hardcoded Unix path rewrites to
    // `C:\data\...`, which would make every row look removable and every
    // path look non-internal. Production (Android) is Linux; tests derive
    // both sides from the same host-absolute base.
    private val hostRoot = File(System.getProperty("user.dir") ?: ".")
    private val legacyPath = File(hostRoot, "legacy-workspace").absolutePath
    private val internalRoot = File(hostRoot, "projects").absolutePath + File.separator
    private val importedPath = File(hostRoot, "notes").absolutePath

    private fun project(
        label: String,
        path: String,
        environment: ProjectRef.EnvironmentLevel = ProjectRef.EnvironmentLevel.L0_EMBEDDED,
    ): ProjectRef = ProjectRef(UUID.randomUUID(), label, File(path), environment)

    private fun setContent(
        state: ProjectsUiState = ProjectsUiState(loading = false),
        importError: String? = null,
        pendingTrust: PendingProjectTrust? = null,
        onBack: () -> Unit = {},
        onImport: () -> Unit = {},
        onOpen: (ProjectRef) -> Unit = {},
        onRemove: (ProjectRef) -> Unit = {},
        onTrustConfirm: () -> Unit = {},
        onTrustDismiss: () -> Unit = {},
    ) {
        compose.setContent {
            ProjectsContent(
                state = state,
                legacyPath = legacyPath,
                internalRoot = internalRoot,
                importError = importError,
                pendingTrust = pendingTrust,
                onBack = onBack,
                onImport = onImport,
                onOpen = onOpen,
                onRemove = onRemove,
                onTrustConfirm = onTrustConfirm,
                onTrustDismiss = onTrustDismiss,
            )
        }
    }

    @Test
    fun `an empty registry explains what the list is for`() {
        setContent()

        compose.onNodeWithText("No projects yet", substring = true).assertIsDisplayed()
        compose.onNodeWithText(
            "the first entry here should always be the legacy workspace",
            substring = true,
        ).assertIsDisplayed()
    }

    @Test
    fun `loading renders the progress copy`() {
        setContent(state = ProjectsUiState(loading = true))

        compose.onNodeWithText("Loading projects...").assertIsDisplayed()
    }

    @Test
    fun `a busy screen disables import`() {
        setContent(state = ProjectsUiState(loading = false, busy = true))

        compose.onNodeWithText("Import folder").assertIsNotEnabled()
    }

    @Test
    fun `registry and import errors are both surfaced`() {
        setContent(
            state = ProjectsUiState(loading = false, error = "registry read failed"),
            importError = "Pick a project folder, not the root of internal storage.",
        )

        compose.onNodeWithText("Registry error: registry read failed").assertIsDisplayed()
        compose.onNodeWithText("Pick a project folder", substring = true).assertIsDisplayed()
    }

    @Test
    fun `populated rows carry label path and environment`() {
        val imported = project(
            "Notes",
            importedPath,
            ProjectRef.EnvironmentLevel.L1_TERMUX_TOOLS,
        )
        setContent(
            state = ProjectsUiState(
                loading = false,
                projects = listOf(project("Legacy workspace", legacyPath), imported),
            )
        )

        compose.onNodeWithText("Legacy workspace").assertIsDisplayed()
        compose.onNodeWithText("Notes").assertIsDisplayed()
        // Path/tail rows can sit below the tall card fold in the test
        // viewport; existence is the parity fact here, the labels carry the
        // displayed assertion.
        compose.onNodeWithText(imported.repoDir.absolutePath).assertExists()
        compose.onNodeWithText("L1_TERMUX_TOOLS workspace").assertExists()
    }

    @Test
    fun `the legacy row opens but never offers remove`() {
        val legacy = project("Legacy workspace", legacyPath)
        val opened = mutableListOf<ProjectRef>()
        setContent(
            state = ProjectsUiState(loading = false, projects = listOf(legacy)),
            onOpen = { opened += it },
        )

        compose.onAllNodesWithContentDescription("Remove Legacy workspace").assertCountEquals(0)
        compose.onNodeWithText("Legacy workspace").performClick()
        compose.waitForIdle()

        assertEquals(listOf(legacy), opened)
    }

    @Test
    fun `an imported row remove is confirmed named and cancel-safe`() {
        val imported = project("Notes", importedPath)
        val removed = mutableListOf<ProjectRef>()
        setContent(
            state = ProjectsUiState(loading = false, projects = listOf(imported)),
            onRemove = { removed += it },
        )

        compose.onAllNodesWithContentDescription("Remove Notes")[0].performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Remove project").assertIsDisplayed()
        compose.onNodeWithText("stay untouched", substring = true).assertIsDisplayed()

        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()

        assertTrue("cancel must not remove", removed.isEmpty())
        compose.onAllNodesWithText("Remove project").assertCountEquals(0)

        compose.onAllNodesWithContentDescription("Remove Notes")[0].performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Remove").performClick()
        compose.waitForIdle()

        assertEquals(listOf(imported), removed)
        compose.onAllNodesWithText("Remove project").assertCountEquals(0)
    }

    @Test
    fun `an internal project names the workspace folder it deletes`() {
        val internal = project(
            "Old minted project",
            File(File(hostRoot, "projects"), "abc/repo").absolutePath,
        )
        setContent(
            state = ProjectsUiState(loading = false, projects = listOf(internal)),
        )

        compose.onAllNodesWithContentDescription("Remove Old minted project")[0].performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Remove project").assertIsDisplayed()
        compose.onNodeWithText("is an app-internal project", substring = true)
            .assertExists()
        compose.onNodeWithText("DELETES its generated workspace folder", substring = true)
            .assertExists()
    }

    @Test
    fun `busy disables the removal confirm`() {
        val imported = project("Notes", importedPath)
        setContent(
            state = ProjectsUiState(loading = false, busy = true, projects = listOf(imported)),
        )

        compose.onAllNodesWithContentDescription("Remove Notes")[0].performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Remove").assertIsNotEnabled()
    }

    @Test
    fun `import and back dispatch their host callbacks`() {
        var imports = 0
        var backs = 0
        setContent(onBack = { backs += 1 }, onImport = { imports += 1 })

        compose.onNodeWithText("Import folder").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()

        assertEquals(1, imports)
        assertEquals(1, backs)
    }

    @Test
    fun `the trust dialog renders the shared copy and confirms once`() {
        val target = project("Notes", importedPath)
        var confirms = 0
        var dismisses = 0
        setContent(
            pendingTrust = PendingProjectTrust(
                ref = target,
                kind = TrustReason.CHANGED_SINCE_CONSENT,
            ),
            onTrustConfirm = { confirms += 1 },
            onTrustDismiss = { dismisses += 1 },
        )

        compose.onNodeWithText("This folder carries a crushrc").assertIsDisplayed()
        compose.onNodeWithText("Notes", substring = true).assertExists()
        compose.onNodeWithText(
            "The crushrc CHANGED since your last approval",
            substring = true,
        ).assertExists()

        compose.onNodeWithText("Run it and open").performClick()
        compose.waitForIdle()

        assertEquals(1, confirms)
        assertEquals(0, dismisses)
    }
}
