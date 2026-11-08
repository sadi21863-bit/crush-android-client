package com.crush.mobile.domain.file

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The Review store's gates, as pure state behavior: no Compose, no
 * ViewModel, one real temp directory for the revert path so a successful
 * revert actually touches a file.
 */
class ReviewHistoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var history: ReviewHistory

    private val set = FileChangeSet(
        sessionId = null,
        diffs = listOf(
            FileDiff(path = "a.txt", hunks = emptyList(), added = 1, removed = 0,
                beforeLineCount = 1, afterLineCount = 2, created = false, deleted = false)
        ),
        unreadable = emptyList(),
        snapshotUnavailable = false,
        capturedAtMs = 1000L
    )
    private val beforeTexts: Map<String, String?> = mapOf("a.txt" to "before")
    private val afterTexts: Map<String, String?> = mapOf("a.txt" to "before")

    @Before
    fun setUp() {
        root = tmp.newFolder("workspace").canonicalFile
        history = ReviewHistory()
        runBlocking { history.clear() }
    }

    private fun record(
        target: ReviewHistory = history,
        sessionId: String? = "sess-1",
        startedAtMs: Long = 500L,
        rootPath: String = root.absolutePath,
        changes: FileChangeSet = set
    ): Long? = target.recordTurn(
        sessionId = sessionId,
        startedAtMs = startedAtMs,
        rootPath = rootPath,
        changes = changes,
        beforeTexts = beforeTexts,
        afterTexts = afterTexts
    )

    // ---- the fly flag and supersede semantics ------------------------------

    @Test
    fun `runSettled lowers the fly flag so revert proceeds`() = runBlocking {
        // The reverter's guard demands the file exist as the turn left it.
        File(root, "a.txt").writeText("before")
        history.beginTurn()
        val id = record()
        assertTrue(history.runInFlight.value)
        history.runSettled()

        val outcome = history.revert(id!!, "a.txt")

        assertTrue(outcome is FileReverter.Outcome.Done)
    }

    @Test
    fun `revert during a running turn is refused with a running sentence`() = runBlocking {
        File(root, "a.txt").writeText("before")
        history.runSettled()
        val id = record()
        history.beginTurn()

        val outcome = history.revert(id!!, "a.txt")

        assertTrue(outcome is FileReverter.Outcome.Refused)
        assertTrue(
            (outcome as FileReverter.Outcome.Refused).reason.contains("turn is running")
        )
        assertEquals("the agent's file survives a mid-run refusal", "before", File(root, "a.txt").readText())
    }

    @Test
    fun `a new turn supersedes the previous card`() = runBlocking {
        val first = record()
        history.runSettled()
        history.beginTurn()
        val second = record()
        history.runSettled()

        assertFalse(
            "the newest turn is revertible",
            history.cards.value.first { it.id == second }.superseded
        )
        assertTrue(
            "the older turn degraded to read-only",
            history.cards.value.first { it.id == first }.superseded
        )
    }

    @Test
    fun `a superseded card refuses revert and says so`() = runBlocking {
        val first = record()
        history.runSettled()
        history.beginTurn()
        val second = record()
        history.runSettled()

        val outcome = history.revert(first!!, "a.txt")

        assertTrue(outcome is FileReverter.Outcome.Refused)
        assertTrue(
            (outcome as FileReverter.Outcome.Refused).reason.contains("older turn")
        )
    }

    // ---- record shape ------------------------------------------------------

    @Test
    fun `an empty outcome is not recorded`() {
        val empty = set.copy(unreadable = listOf("gone.txt"), diffs = emptyList())
        // unreadable-only still tells the user something: the card stands.
        assertNull(
            "a totally empty change set records nothing",
            history.recordTurn(
                null, 0L, root.absolutePath,
                set.copy(unreadable = emptyList(), diffs = emptyList()),
                emptyMap(), emptyMap()
            )
        )
        val id = history.recordTurn(
            null, 0L, root.absolutePath, empty, emptyMap(), emptyMap()
        )
        assertTrue(id != null)
        assertTrue(history.cards.value.first().changes.unreadable.isNotEmpty())
    }

    @Test
    fun `cards are newest first`() {
        record(startedAtMs = 100L)
        record(startedAtMs = 200L)

        assertEquals(listOf(200L, 100L), history.cards.value.map { it.startedAtMs })
    }

    // ---- revert outcomes on the card --------------------------------------

    @Test
    fun `a reverted path is marked and a second revert is refused`() = runBlocking {
        File(root, "a.txt").writeText("before")
        val id = record()

        val first = history.revert(id!!, "a.txt")
        assertTrue(first is FileReverter.Outcome.Done)

        val card = history.cards.value.first { it.id == id }
        assertTrue(card.reverted.contains("a.txt"))
        assertTrue(card.notes["a.txt"]?.ok == true)
        assertTrue(card.notes["a.txt"]?.message?.contains("restored") == true)

        val second = history.revert(id, "a.txt")

        assertTrue(second is FileReverter.Outcome.Refused)
        assertTrue(
            (second as FileReverter.Outcome.Refused).reason.contains("already reverted")
        )
    }

    @Test
    fun `a refusal is kept as a note with its exact reason`() = runBlocking {
        val id = record()
        // Post-turn edit: the file on disk no longer matches the after-image.
        File(root, "a.txt").writeText("someone edited after the turn")

        val outcome = history.revert(id!!, "a.txt")

        assertTrue(outcome is FileReverter.Outcome.Refused)
        val refused = outcome as FileReverter.Outcome.Refused
        val note = history.cards.value.first().notes["a.txt"]
        assertEquals(false, note?.ok)
        assertEquals(refused.reason, note?.message)
        assertEquals(
            "the newer edit survives a refusal",
            "someone edited after the turn",
            File(root, "a.txt").readText()
        )
    }

    @Test
    fun `a path that is not part of the card is refused`() = runBlocking {
        val id = record()

        val outcome = history.revert(id!!, "not/mine.txt")

        assertTrue(outcome is FileReverter.Outcome.Refused)
        assertTrue(
            (outcome as FileReverter.Outcome.Refused).reason.contains("not part of")
        )
    }

    @Test
    fun `an unknown card id is refused`() = runBlocking {
        val outcome = history.revert(999L, "a.txt")

        assertTrue(outcome is FileReverter.Outcome.Refused)
        assertTrue(
            (outcome as FileReverter.Outcome.Refused).reason.contains("no longer in the history")
        )
    }

    // ---- the operation gate: revert vs a beginning run ----------------------

    @Test
    fun `two concurrent reverts of one path stay serialized and honest`() = runBlocking {
        File(root, "a.txt").writeText("before")
        val events = CopyOnWriteArrayList<String>()
        val gated = ReviewHistory(TracedReverter(events))
        val id = record(gated)!!

        val first = async(Dispatchers.IO) { gated.revert(id, "a.txt") }
        val second = async(Dispatchers.IO) { gated.revert(id, "a.txt") }
        val outcomes = listOf(first.await(), second.await())

        // Serialized: no two file operations ever overlapped, and the loser
        // got the same honest sentence a sequential second revert returns.
        assertNoOverlappingIo(events)
        assertEquals(1, outcomes.filterIsInstance<FileReverter.Outcome.Done>().size)
        val refused = outcomes.filterIsInstance<FileReverter.Outcome.Refused>()
        assertEquals(1, refused.size)
        assertTrue(refused.single().reason.contains("already reverted"))

        // Nothing corrupted: the file was restored once, the card holds one
        // reverted mark (not doubled) and the loser's note, never two.
        assertEquals("before", File(root, "a.txt").readText())
        val card = gated.cards.value.first { it.id == id }
        assertEquals(1, card.reverted.size)
        assertTrue(card.reverted.contains("a.txt"))
        assertEquals(1, card.notes.size)
        assertEquals(refused.single().reason, card.notes["a.txt"]?.message)
    }

    @Test
    fun `a record turn waits for a revert's file operation to finish`() = runBlocking {
        File(root, "a.txt").writeText("before")
        val events = CopyOnWriteArrayList<String>()
        val release = CountDownLatch(1)
        val gated = ReviewHistory(TracedReverter(events, release))
        val id = record(gated)!!
        gated.runSettled()

        val revertOutcome = async(Dispatchers.IO) { gated.revert(id, "a.txt") }
        awaitEvent(events, "io start")
        // Finding B's race, at the store's own seam: with the revert's file
        // work held open mid-flight, a new turn now tries to begin.
        val beginTurn = async(Dispatchers.IO) { gated.beginTurn(); events.add("begin done") }
        delay(200)
        assertFalse("a run began over an in-flight revert", gated.runInFlight.value)

        release.countDown()
        val outcome = revertOutcome.await()
        beginTurn.join()

        assertTrue(outcome is FileReverter.Outcome.Done)
        assertTrue("the landed turn raised the flag", gated.runInFlight.value)
        assertTrue(
            "the run began before the revert's IO was over",
            events.indexOf("io end") < events.indexOf("begin done")
        )
        val card = gated.cards.value.first { it.id == id }
        assertTrue("the revert's done note did not survive the turn", card.notes["a.txt"]?.ok == true)
        assertTrue("the earlier turn was not superseded by the landed one", card.superseded)
    }

    /** Awaits (bounded) until the traced reverter announces a marker. */
    private suspend fun awaitEvent(events: List<String>, marker: String) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!events.contains(marker) && System.currentTimeMillis() < deadline) delay(10)
        assertTrue("timed out waiting for \"$marker\"", events.contains(marker))
    }

    /** No two reverter file-operation windows may ever overlap. */
    private fun assertNoOverlappingIo(events: List<String>) {
        var depth = 0
        for (marker in events) {
            depth += if (marker == "io start") 1 else -1
            assertTrue("revert file-operations overlapped: $events", depth in 0..1)
        }
        assertEquals("unbalanced io markers: $events", 0, depth)
    }

    /**
     * A [FileReverter] that announces its file-operation window (and can hold
     * it open) so the gate tests above can observe the store's serialization
     * directly, thread-safely across the real IO pool.
     */
    private class TracedReverter(
        private val events: MutableList<String>,
        private val release: CountDownLatch = CountDownLatch(0)
    ) : FileReverter() {
        override fun revert(
            root: File,
            path: String,
            beforeText: String?,
            afterText: String?
        ): FileReverter.Outcome {
            events.add("io start")
            release.await(5, TimeUnit.SECONDS)
            val outcome = super.revert(root, path, beforeText, afterText)
            events.add("io end")
            return outcome
        }
    }
}
