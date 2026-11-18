package com.crush.mobile.ui.screens.review

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.crush.mobile.domain.file.DiffHunk
import com.crush.mobile.domain.file.DiffLine
import com.crush.mobile.domain.file.FileChangeSet
import com.crush.mobile.domain.file.FileDiff
import com.crush.mobile.domain.file.RevertNote
import com.crush.mobile.domain.file.TurnChanges
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 2b.3 (V2 plan §6.5): the Review content layer renders from fabricated
 * [ReviewUiState] and dispatches host callbacks; no history store, container,
 * Context, or SAF launcher crosses the seam.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h1600dp")
class ReviewContentTest {

    @get:Rule
    val compose = createComposeRule()

    private fun diff(path: String = "a.txt", added: Int = 1, removed: Int = 0): FileDiff =
        FileDiff(
            path = path,
            hunks = listOf(
                DiffHunk(
                    beforeStart = 1,
                    afterStart = 1,
                    lines = listOf(
                        DiffLine(DiffLine.Kind.ADDED, "new line", null, 1)
                    ),
                )
            ),
            added = added,
            removed = removed,
            beforeLineCount = 1,
            afterLineCount = 2,
            created = false,
            deleted = false,
        )

    private fun card(
        id: Long = 1L,
        superseded: Boolean = false,
        reverted: Set<String> = emptySet(),
        notes: Map<String, RevertNote> = emptyMap(),
        changes: FileChangeSet = FileChangeSet(
            sessionId = "sess-1",
            diffs = listOf(diff()),
            unreadable = emptyList(),
            snapshotUnavailable = false,
            capturedAtMs = 1000L,
        ),
    ): TurnChanges = TurnChanges(
        id = id,
        startedAtMs = 1_700_000_000_000L,
        sessionId = "sess-1",
        rootPath = "/storage/emulated/0/Notes",
        changes = changes,
        beforeTexts = mapOf("a.txt" to "old line"),
        afterTexts = mapOf("a.txt" to "new line"),
        reverted = reverted,
        superseded = superseded,
        notes = notes,
    )

    private fun setContent(
        state: ReviewUiState,
        onBack: () -> Unit = {},
        onRevert: (Long, String) -> Unit = { _, _ -> },
        onExport: (String, String) -> Unit = { _, _ -> },
    ) {
        compose.setContent {
            ReviewContent(
                state = state,
                onBack = onBack,
                onRevert = onRevert,
                onExport = onExport,
            )
        }
    }

    @Test
    fun `an empty history explains the process scope`() {
        setContent(ReviewUiState())

        compose.onNodeWithText("Nothing collected yet.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Send a prompt in a chat", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a populated card carries its turn header counts and row actions`() {
        setContent(ReviewUiState(cards = listOf(card())))

        compose.onNodeWithText("Turn at ", substring = true).assertExists()
        compose.onNodeWithText("1 file(s) +1 -0").assertExists()
        compose.onNodeWithText("a.txt").assertExists()
        compose.onNodeWithText("Revert this file").assertIsEnabled()
        compose.onNodeWithText("Export a copy").assertIsEnabled()
    }

    @Test
    fun `a superseded card says it is read-only and disables revert`() {
        setContent(ReviewUiState(cards = listOf(card(superseded = true))))

        compose.onNodeWithText("Older turn - read-only", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithText("Revert this file").assertIsNotEnabled()
    }

    @Test
    fun `a running turn disables revert with an explicit banner`() {
        setContent(ReviewUiState(cards = listOf(card()), runInFlight = true))

        compose.onNodeWithText("A turn is running; revert is disabled", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithText("Revert this file").assertIsNotEnabled()
    }

    @Test
    fun `unreadable and no-diff cards keep their honest explanation`() {
        val changes = FileChangeSet(
            sessionId = "sess-1",
            diffs = emptyList(),
            unreadable = listOf("binary.bin"),
            snapshotUnavailable = false,
            capturedAtMs = 1000L,
        )
        setContent(ReviewUiState(cards = listOf(card(changes = changes))))

        compose.onNodeWithText("could not be diffed", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithText("No diffable file change in this turn.").assertIsDisplayed()
    }

    @Test
    fun `a reverted path is labelled and cannot revert twice`() {
        val note = RevertNote(ok = true, message = "restored the before-image")
        setContent(
            ReviewUiState(
                cards = listOf(
                    card(
                        reverted = setOf("a.txt"),
                        notes = mapOf("a.txt" to note),
                    )
                )
            )
        )

        compose.onNodeWithText("Reverted").assertExists()
        compose.onNodeWithText("Done: restored the before-image").assertIsDisplayed()
        compose.onNodeWithText("Reverted").assertIsNotEnabled()
    }

    @Test
    fun `revert is confirmed named and cancel-safe`() {
        val dispatched = mutableListOf<Pair<Long, String>>()
        setContent(
            ReviewUiState(cards = listOf(card(id = 7L))),
            onRevert = { id, path -> dispatched += id to path },
        )

        compose.onNodeWithText("Revert this file").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Revert this file?").assertIsDisplayed()
        compose.onNodeWithText("\"a.txt\"", substring = true).assertExists()

        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertEquals(0, dispatched.size)

        compose.onNodeWithText("Revert this file").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Revert").performClick()
        compose.waitForIdle()

        assertEquals(listOf(7L to "a.txt"), dispatched)
    }

    @Test
    fun `export carries the frozen root and relative path`() {
        val exported = mutableListOf<Pair<String, String>>()
        setContent(
            ReviewUiState(cards = listOf(card())),
            onExport = { root, path -> exported += root to path },
        )

        compose.onNodeWithText("Export a copy").performClick()
        compose.waitForIdle()

        assertEquals(
            listOf("/storage/emulated/0/Notes" to "a.txt"),
            exported
        )
    }

    @Test
    fun `the export outcome message is rendered`() {
        setContent(
            ReviewUiState(
                cards = listOf(card()),
                exportMessage = "Exported a copy of a.txt.",
            )
        )

        compose.onNodeWithText("Exported a copy of a.txt.").assertIsDisplayed()
    }

    @Test
    fun `back dispatches the host callback`() {
        var backs = 0
        setContent(ReviewUiState(), onBack = { backs += 1 })

        compose.onNodeWithText("Back").performClick()
        compose.waitForIdle()

        assertEquals(1, backs)
    }

}
