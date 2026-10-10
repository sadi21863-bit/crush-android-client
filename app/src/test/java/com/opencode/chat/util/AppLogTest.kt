package com.opencode.chat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Log rotation must keep the newest lines.
 *
 * THE BUG THIS PINS
 *
 * The cap used to be `if (f.length() > MAX_BYTES) f.writeText("")` - it erased
 * the whole buffer. For a crash diagnostic that is precisely backwards: the cap
 * only trips once a fault has been running long enough to fill 512KB, and the
 * lines that explain it are the most recent ones. The implementation deleted
 * the evidence at the exact moment it stopped being noise.
 *
 * The second defect was a stat() syscall on every single write, plus an
 * unsynchronised append that could interleave mid-line from the heartbeat and
 * SSE threads.
 */
class RotateKeepingTailTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun budget() = 64

    /**
     * Zero-padded so a marker is never a substring of another. The first version
     * of this test asserted `contains("line-1")`, which also matches "line-100" -
     * so it failed on a correct implementation. A test that cannot tell the
     * newest line from the oldest is not testing rotation at all.
     */
    private fun writeLines(f: File, n: Int, prefix: String = "line") {
        f.writeText((1..n).joinToString("\n", postfix = "\n") {
            "%s-%04d".format(prefix, it)
        })
    }

    @Test
    fun `the newest entry always survives rotation`() {
        val f = tmp.newFile()
        writeLines(f, 200)
        val text = rotateKeepingTail(f, null, "THE-NEWEST", budget())

        // The whole point: a small budget must not cost you the latest line.
        assertTrue("newest entry was lost", text.contains("THE-NEWEST"))
        assertTrue("file must contain the newest entry", f.readText().contains("THE-NEWEST"))
    }

    @Test
    fun `rotation keeps the tail and drops the head`() {
        val f = tmp.newFile()
        writeLines(f, 100)
        val text = rotateKeepingTail(f, null, "entry", budget())

        // Early history is what gets evicted, by design.
        assertTrue("old history should be evicted", !text.contains("line-0001"))
        assertTrue("the newest kept line should be line-0100", text.contains("line-0100"))
    }

    @Test
    fun `the rotated-out head is archived, not destroyed`() {
        val f = tmp.newFile()
        val archive = File(tmp.root, "archive.log")
        writeLines(f, 100)
        rotateKeepingTail(f, archive, "entry", budget())

        assertTrue("archive must exist", archive.exists())
        assertTrue("archive must hold the evicted head", archive.readText().contains("line-0001"))
        assertTrue("live log must stay small", !f.readText().contains("line-0001"))
    }

    @Test
    fun `the archive holds the head and the live log holds the tail`() {
        // The two files must not simply duplicate each other, which is what the
        // implementation actually did: it archived the WHOLE file and then
        // rewrote the tail into the original, so "archive" contained everything
        // including the lines the live log was supposed to have kept. Verified
        // separately because "archive exists" passes for that bug too.
        val f = tmp.newFile()
        val archive = File(tmp.root, "archive.log")
        writeLines(f, 100)
        rotateKeepingTail(f, archive, "entry", budget())

        val archivedLines = archive.readLines().toSet()
        val liveLines = f.readLines().toSet()
        assertTrue("archive must be a strict subset", archivedLines.isNotEmpty())
        assertTrue(
            "the newest line must be in the live log, not only the archive",
            liveLines.any { it.contains("line-0100") } && !archivedLines.any { it.contains("line-0100") }
        )
        assertTrue(
            "the oldest line must be in the archive, not the live log",
            archivedLines.any { it.contains("line-0001") } && !liveLines.any { it.contains("line-0001") }
        )
    }

    @Test
    fun `an existing archive is replaced, not appended to`() {
        val f = tmp.newFile()
        val archive = tmp.newFile("archive.log")
        archive.writeText("stale content that must not survive\n")
        writeLines(f, 100)
        rotateKeepingTail(f, archive, "entry", budget())

        assertTrue("archive must be replaced", !archive.readText().contains("stale content"))
    }

    @Test
    fun `an empty log takes just the newest entry`() {
        val f = File(tmp.root, "empty.log")
        val text = rotateKeepingTail(f, null, "first-line", budget())
        assertEquals("first-line\n", text)
    }

    @Test
    fun `a non-existent log does not throw`() {
        val missing = File(tmp.root, "nope.log")
        val text = rotateKeepingTail(missing, null, "still-works", budget())
        assertTrue(text.contains("still-works"))
    }

    @Test
    fun `rotation is repeatable without growing the log`() {
        val f = tmp.newFile()
        f.writeText("")
        val budget = 128
        var size = 0L
        repeat(50) { i ->
            val body = "entry-$i " + "x".repeat(10)
            if (size + body.length > budget) {
                rotateKeepingTail(f, null, body, budget / 2)
                size = f.length()
            } else {
                f.appendText(body + "\n")
                size += body.length + 1
            }
        }
        // Never unbounded: that is the property an erase-and-reappend loop loses.
        assertTrue("log grew beyond the budget: $size", size <= budget)
        assertTrue(f.readText().contains("entry-49"))
    }

    @Test
    fun `a single line larger than the budget is still kept`() {
        // Losing the one line that matters because it is big is the same bug.
        val f = tmp.newFile()
        writeLines(f, 50)
        val huge = "E/EngineSession: " + "z".repeat(500)
        val text = rotateKeepingTail(f, null, huge, budget())
        assertTrue("an oversized entry must not be dropped", text.contains(huge))
    }
}
