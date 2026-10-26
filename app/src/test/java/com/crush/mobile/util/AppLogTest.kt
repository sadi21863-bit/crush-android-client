package com.crush.mobile.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `a second rotation the same day appends and erases nothing`() {
        // The archive sink is a DATED DAY FILE that several rotations and runs
        // contribute to. The first cut of the retention design re-used the
        // delete-then-write scratch-buffer semantics and would have erased
        // every earlier entry of the day.
        val f = File(tmp.root, "run.log")
        val archive = File(tmp.root, "app-archive-2026-10-25.log")

        f.writeText((1..100).joinToString("\n", postfix = "\n") { "A-%04d".format(it) })
        rotateKeepingTail(f, archive, "A-NEWEST", budget())

        f.writeText((1..100).joinToString("\n", postfix = "\n") { "B-%04d".format(it) })
        rotateKeepingTail(f, archive, "B-NEWEST", budget())

        val archived = archive.readText()
        assertTrue("first rotation's head must survive", archived.contains("A-0001"))
        assertTrue("second rotation's head must survive", archived.contains("B-0001"))
        assertTrue("live log must not duplicate the archived head", !f.readText().contains("A-0001"))
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

/**
 * Archive retention (2026-10-25).
 *
 * WHY: the user tests this app standalone, then asks what happened days later.
 * Before this, only `app.log` + one overwriteable `app-prev.log` ever existed -
 * the Oct-23 standalone evidence was erased from disk before it could be
 * audited. Dated `app-archive-<day>.log` files must accumulate and be deleted
 * only when they expire (30 days) or push the total past the storage cap.
 */
class RetentionPruneTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Fixed epoch: timestamps must never come from the wall clock in a test. */
    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun mk(name: String, ageMs: Long, bytes: Int = 100): File {
        val f = File(tmp.root, name)
        f.writeText("x".repeat(bytes))
        f.setLastModified(now - ageMs)
        return f
    }

    @Test
    fun `archives past the retention window are deleted`() {
        val old = mk("app-archive-2026-09-20.log", ageMs = 31 * day)
        val fresh = mk("app-archive-2026-10-24.log", ageMs = 1 * day)

        val deleted = pruneArchives(tmp.root, now, retentionMs = 30 * day, capBytes = 1 shl 30)

        assertEquals(1, deleted)
        assertFalse("expired archive must go", old.exists())
        assertTrue("recent archive must stay", fresh.exists())
    }

    @Test
    fun `an archive exactly at the boundary is kept`() {
        // `age > retentionMs` is the rule: something stored 30 full days ago is
        // not older than 30 days, so deleting it early would be off-by-one
        // against the promise made to the user.
        val boundary = mk("app-archive-2026-09-25.log", ageMs = 30 * day)
        pruneArchives(tmp.root, now, retentionMs = 30 * day, capBytes = 1 shl 30)
        assertTrue(boundary.exists())
    }

    @Test
    fun `the size cap evicts the oldest first`() {
        val oldest = mk("app-archive-2026-10-01.log", ageMs = 2 * day, bytes = 1000)
        val middle = mk("app-archive-2026-10-02.log", ageMs = 1 * day, bytes = 1000)
        val newest = mk("app-archive-2026-10-03.log", ageMs = 12 * 60 * 60 * 1000L, bytes = 1000)

        pruneArchives(tmp.root, now, retentionMs = 30 * day, capBytes = 2500)

        assertTrue("oldest must be evicted first", !oldest.exists())
        assertTrue("middle must survive a cap of 2500 for 1000+1000 remaining", middle.exists())
        assertTrue("newest must survive", newest.exists())
    }

    @Test
    fun `files that are not dated archives are never touched`() {
        // app.log itself may carry an old mtime (the file survives runs).
        val decoy = mk("app.log", ageMs = 60 * day)
        val engineState = mk("crush-state.bin", ageMs = 60 * day)

        assertEquals(0, pruneArchives(tmp.root, now, 30 * day, 1 shl 30))

        assertTrue(decoy.exists())
        assertTrue(engineState.exists())
    }

    @Test
    fun `a missing directory is not a crash`() {
        val missing = File(tmp.root, "nope")
        assertEquals(0, pruneArchives(missing, now, 30 * day, 1 shl 30))
    }

    @Test
    fun `archive names sort chronologically because they are date-stamped`() {
        val a = archiveFileName(1_799_000_000_000L)
        val b = archiveFileName(1_800_000_000_000L)
        assertTrue(a.matches(Regex("app-archive-\\d{4}-\\d{2}-\\d{2}\\.log")))
        assertTrue("same-zone day strings must order with time", b >= a)
    }
}
