package com.crush.mobile.domain.file

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The revert truth table.
 *
 * These tests use a real temp directory rather than a mocked File, because
 * every behavior they pin is a filesystem property: byte-not-byte comparison
 * on the text plane, parent re-creation, delete success, and what a refusal
 * must NOT touch.
 *
 * The terminology throughout matches the collector's: BEFORE is the pre-turn
 * image, AFTER is what the turn left at collect time, CURRENT is what is on
 * disk when the revert runs.
 */
class FileReverterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private val reverter = FileReverter()

    @Before
    fun setUp() {
        root = tmp.newFolder("workspace").canonicalFile
    }

    private fun write(rel: String, content: String): File {
        val f = File(root, rel)
        f.parentFile?.mkdirs()
        f.writeText(content)
        return f
    }

    private fun read(rel: String): String = File(root, rel).readText()

    // ---- modified -----------------------------------------------------------

    @Test
    fun `a modified file is restored to its pre-turn text`() {
        write("a.txt", "before")
        val before = "before"
        val after = "the agent wrote this"
        write("a.txt", after)

        val outcome = reverter.revert(root, "a.txt", before, after)

        assertTrue(outcome is FileReverter.Outcome.Done)
        assertEquals(
            FileReverter.Outcome.Action.RESTORED_MODIFIED,
            (outcome as FileReverter.Outcome.Done).action
        )
        assertEquals("before", read("a.txt"))
    }

    @Test
    fun `a modified file whose content moved on since the turn is refused untouched`() {
        write("a.txt", "before")
        val after = "what the turn left"
        write("a.txt", after)
        // Someone edited after collect: CURRENT no longer equals AFTER.
        write("a.txt", "someone else's edit")

        val outcome = reverter.revert(root, "a.txt", "before", after)

        assertTrue(outcome is FileReverter.Outcome.Refused)
        assertTrue(
            (outcome as FileReverter.Outcome.Refused).reason.contains("changed after the turn")
        )
        // The newer edit survives untouched - a refusal is a non-action.
        assertEquals("someone else's edit", read("a.txt"))
    }

    @Test
    fun `a modified file that is now missing is refused`() {
        // The turn modified it; then something deleted it. Restoring a
        // before-image into that void was never the user's ask.
        val outcome = reverter.revert(root, "gone.txt", "before", "what the turn left")

        assertTrue(outcome is FileReverter.Outcome.Refused)
        assertFalse(File(root, "gone.txt").exists())
    }

    @Test
    fun `an empty after-state restores the before text`() {
        // A turn can leave an EMPTY file: that is still the modified branch
        // (the file exists), not a deletion - and reverting must write the
        // pre-turn content back, not touch nothing.
        val before = "before"
        write("a.txt", "")

        val outcome = reverter.revert(root, "a.txt", before, "")

        assertTrue(outcome is FileReverter.Outcome.Done)
        assertEquals("before", read("a.txt"))
    }

    // ---- created ------------------------------------------------------------

    @Test
    fun `a created file is deleted`() {
        write("new.txt", "born during the turn")

        val outcome = reverter.revert(root, "new.txt", null, "born during the turn")

        assertTrue(outcome is FileReverter.Outcome.Done)
        assertEquals(
            FileReverter.Outcome.Action.DELETED_CREATED,
            (outcome as FileReverter.Outcome.Done).action
        )
        assertFalse(File(root, "new.txt").exists())
    }

    @Test
    fun `a created file edited after the turn is refused`() {
        write("new.txt", "born during the turn")
        val after = "born during the turn"
        // The user (or a later engine run) rewrote it.
        write("new.txt", "later edits")

        val outcome = reverter.revert(root, "new.txt", null, after)

        assertTrue(outcome is FileReverter.Outcome.Refused)
        assertEquals("later edits", read("new.txt"))
    }

    @Test
    fun `a created file that vanished after the turn is refused`() {
        // Nothing exists to delete, but the refusal keeps the report true:
        // this revert did not restore that state, whatever did.
        val outcome = reverter.revert(root, "new.txt", null, "born during the turn")

        assertTrue(outcome is FileReverter.Outcome.Refused)
        assertTrue(
            (outcome as FileReverter.Outcome.Refused).reason.contains("already deleted")
        )
    }

    // ---- deleted ------------------------------------------------------------

    @Test
    fun `a deleted file is restored and its parent directories re-created`() {
        val before =
            "was inside a directory tree the turn took with it"
        // CURRENT state: the path tree itself is gone, which is exactly what
        // the turn produced.
        val outcome = reverter.revert(root, "deep/nested/dir/f.txt", before, null)

        assertTrue(outcome is FileReverter.Outcome.Done)
        assertEquals(
            FileReverter.Outcome.Action.RESTORED_DELETED,
            (outcome as FileReverter.Outcome.Done).action
        )
        assertTrue(File(root, "deep/nested/dir/f.txt").isFile)
        assertEquals(before, read("deep/nested/dir/f.txt"))
    }

    @Test
    fun `a deleted path that exists again is refused`() {
        write("f.txt", "recreated content")

        val outcome = reverter.revert(root, "f.txt", "pre-turn image", null)

        assertTrue(outcome is FileReverter.Outcome.Refused)
        assertTrue(
            (outcome as FileReverter.Outcome.Refused).reason.contains("created after the turn")
        )
        // The recreated file is NOT what this layer may remove.
        assertEquals("recreated content", read("f.txt"))
    }

    // ---- path safety and degenerate calls ----------------------------------

    @Test
    fun `a path escaping the workspace is refused before any IO`() {
        val outside = tmp.newFolder("outside")
        File(outside, "sibling.txt").writeText("innocent")

        val outcome =
            reverter.revert(root, "../outside/sibling.txt", "before", "after")

        assertTrue(outcome is FileReverter.Outcome.Refused)
        assertEquals("innocent", File(outside, "sibling.txt").readText())
    }

    @Test
    fun `no images on a path is refused`() {
        val outcome = reverter.revert(root, "whatever.txt", null, null)

        assertTrue(outcome is FileReverter.Outcome.Refused)
        assertFalse(File(root, "whatever.txt").exists())
    }

    @Test
    fun `subfolder paths revert relative to the given root`() {
        // A card from project A must never write into project B: the root is
        // supplied per card. Here only intra-root nesting is proven; the
        // escape test above pins the boundary.
        val before = "fun before()\n"
        val after = "fun after()\n"
        write("src/Main.kt", after)

        val outcome = reverter.revert(root, "src/Main.kt", before, after)

        assertTrue(outcome is FileReverter.Outcome.Done)
        assertEquals(before, read("src/Main.kt"))
    }
}
