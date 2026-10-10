package com.opencode.chat.domain.file

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Workspace snapshotting and file-change collection.
 *
 * These tests use a real temp directory rather than a mocked File, because every
 * one of the failure modes they cover is a filesystem property: recursion depth,
 * build-artefact skipping, binary detection, path escape, and a file deleted
 * between the two reads.
 */
class FileChangeCollectorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var collector: FileChangeCollector

    private val small = WorkspaceSnapshotter(
        maxFileBytes = 64,
        maxTotalBytes = 4 * 1024,
        maxFiles = 50
    )

    @Before
    fun setUp() {
        root = tmp.newFolder("workspace")
        collector = FileChangeCollector(WorkspaceSnapshotter(maxFileBytes = 64, maxTotalBytes = 4096, maxFiles = 50))
    }

    @After
    fun tearDown() {
        // Nothing to clean; TemporaryFolder handles it.
    }

    private fun write(rel: String, content: String): File {
        val f = File(root, rel)
        f.parentFile?.mkdirs()
        f.writeText(content)
        return f
    }

    // ---- collecting ---------------------------------------------------------

    @Test
    fun `an edited file diffs against its real before-image`() {
        write("a.kt", "line1\nline2\n")
        val before = collector.captureBefore(root)

        write("a.kt", "line1\nCHANGED\n")
        val set = collector.collect(root, listOf("a.kt"), before)

        assertEquals(1, set.diffs.size)
        val diff = set.diffs.first()
        assertEquals(1, diff.added)
        assertEquals(1, diff.removed)
        // This is the property the whole feature exists for: a real before AND
        // after, not one "+1 -0" that pretends the agent wrote the program.
        assertTrue(diff.hunks.isNotEmpty())
        assertEquals("CHANGED", diff.hunks.first().lines.first { it.kind.name == "ADDED" }.text)
    }

    @Test
    fun `an untouched file the engine names yields no diff`() {
        write("a.kt", "same\n")
        val before = collector.captureBefore(root)
        val set = collector.collect(root, listOf("a.kt"), before)
        assertTrue("nothing changed, so nothing should show", set.diffs.isEmpty())
    }

    @Test
    fun `a file created during the turn reads as all additions`() {
        write("old.kt", "x\n")
        val before = collector.captureBefore(root)
        write("new.kt", "brand\nnew\n")
        val set = collector.collect(root, listOf("new.kt"), before)

        assertTrue(set.diffs.first().created)
        assertEquals(2, set.diffs.first().added)
        assertEquals(0, set.diffs.first().removed)
    }

    @Test
    fun `a file deleted during the turn reads as all removals`() {
        val f = write("gone.kt", "was\nhere\n")
        val before = collector.captureBefore(root)
        f.delete()
        val set = collector.collect(root, listOf("gone.kt"), before)

        assertTrue(set.diffs.first().deleted)
        assertEquals(0, set.diffs.first().added)
        assertEquals(2, set.diffs.first().removed)
    }

    @Test
    fun `with no snapshot nothing is fabricated`() {
        write("a.kt", "content\n")
        val set = collector.collect(root, listOf("a.kt"), null)
        assertTrue("no before-image means no diff at all", set.diffs.isEmpty())
        assertTrue(set.snapshotUnavailable)
    }

    @Test
    fun `a file the engine names but we never saw is reported, not dropped`() {
        write("known.kt", "x\n")
        val before = collector.captureBefore(root)
        val set = collector.collect(root, listOf("known.kt", "phantom.kt"), before)

        // "phantom.kt" was never in the workspace before or after. Silently
        // omitting it would hide a change the engine says happened.
        assertEquals(listOf("phantom.kt"), set.unreadable)
        assertTrue(set.diffs.isEmpty())
    }

    @Test
    fun `a path escaping the workspace is refused`() {
        write("a.kt", "x\n")
        val before = collector.captureBefore(root)
        val set = collector.collect(root, listOf("../../etc/passwd"), before)
        assertEquals(1, set.unreadable.size)
        assertTrue(set.diffs.isEmpty())
    }

    @Test
    fun `a nested file resolves through its relative path`() {
        write("src/main/kotlin/A.kt", "a\nb\n")
        val before = collector.captureBefore(root)
        write("src/main/kotlin/A.kt", "a\nCHANGED\n")
        val set = collector.collect(root, listOf("src/main/kotlin/A.kt"), before)
        assertEquals("src/main/kotlin/A.kt", set.diffs.first().path)
    }

    @Test
    fun `duplicate paths are deduplicated`() {
        write("a.kt", "x\n")
        val before = collector.captureBefore(root)
        write("a.kt", "y\n")
        val set = collector.collect(root, listOf("a.kt", "a.kt", "./a.kt"), before)
        assertEquals(1, set.diffs.size)
    }

    @Test
    fun `an empty tracked list yields an empty set`() {
        write("a.kt", "x\n")
        val before = collector.captureBefore(root)
        assertTrue(collector.collect(root, emptyList(), before).isEmpty)
    }

    // ---- snapshotting -------------------------------------------------------

    @Test
    fun `a binary file is not snapshot as text`() {
        // NUL byte: the one reliable binary marker. The first version of the check
        // looked for a SPACE, which would have marked every source file binary and
        // suppressed every diff.
        val f = File(root, "bin.dat").apply { writeBytes(byteArrayOf(0, 1, 2, 3, 0)) }
        val snap = small.capture(root)
        assertTrue("a file with NULs must not be snapshot", !snap.contents.containsKey("bin.dat"))
        assertTrue(f.exists())
    }

    @Test
    fun `a text file IS snapshot despite containing plenty of spaces`() {
        write("ok.kt", "fun main() { println(\"hello world\") }\n")
        val snap = small.capture(root)
        assertNotNull(snap.contents["ok.kt"])
    }

    @Test
    fun `a file over the per-file cap is marked, not stored`() {
        write("big.kt", "x".repeat(500))
        val snap = small.capture(root)
        assertFalse(snap.contents.containsKey("big.kt"))
        assertTrue(snap.truncatedPaths.contains("big.kt"))
    }

    @Test
    fun `an oversized tracked file is reported as unreadable rather than faked`() {
        write("big.kt", "x".repeat(500))
        val before = small.capture(root)
        write("big.kt", "y".repeat(500))
        val set = collector.collect(root, listOf("big.kt"), before)
        assertEquals(listOf("big.kt"), set.unreadable)
    }

    @Test
    fun `build and dependency directories are skipped`() {
        // These are the dominant source of large files and of diffs nobody wants.
        write("build/output.txt", "x".repeat(20))
        write("node_modules/pkg/index.js", "y")
        write(".git/COMMIT_EDITMSG", "z")
        val snap = small.capture(root)
        assertTrue(snap.contents.none { it.key.contains("build/") })
        assertTrue(snap.contents.none { it.key.contains("node_modules/") })
        assertTrue(snap.contents.none { it.key.contains(".git/") })
    }

    @Test
    fun `an empty workspace snapshots without throwing`() {
        val snap = small.capture(tmp.newFolder("empty"))
        assertTrue(snap.contents.isEmpty())
    }

    @Test
    fun `a missing workspace root snapshots empty`() {
        val snap = small.capture(File(tmp.root, "does-not-exist"))
        assertTrue(snap.contents.isEmpty())
    }

    @Test
    fun `the real budget reads back at the same ceiling`() {
        val caps = small.capture(root)
        assertTrue(caps.contents.values.all { it.length <= 64 })
    }

    private fun <T> assertTrue_(msg: String, cond: Boolean) = assertTrue(msg, cond)
}
