package com.crush.mobile.domain.file

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Line diffing.
 *
 * The engine exposes no diff endpoint - it only names the files a session
 * touched. The app reads those off its own app-private workspace, so the diff is
 * ours to compute. These tests pin the properties that make a diff TRUSTWORTHY
 * rather than merely plausible:
 *
 *  - line numbers are 1-based and point at the line they describe
 *  - a hunk only contains changed lines plus context, never the whole file
 *  - an identical body is reported unchanged, not as a diff
 *  - a region too large to diff is labelled, not silently approximated
 *  - trailing-newline-only differences are not edits
 */
class LineDifferTest {

    private fun d(before: String, after: String) = LineDiffer.diff("f.kt", before, after)

    @Test
    fun `an identical body is unchanged`() {
        assertTrue(d("a\nb\nc", "a\nb\nc").unchanged)
        assertFalse(d("a\nb\nc", "a\nb\nc").hasChanges)
    }

    @Test
    fun `a trailing newline is not an edit`() {
        // "a\n" and "a" are the same file to every tool a human would use.
        assertTrue(d("a\n", "a").unchanged)
        assertTrue(d("a", "a\n").unchanged)
    }

    @Test
    fun `a one-line change yields one hunk with context`() {
        val r = d((1..20).joinToString("\n") { "line$it" }, (1..20).joinToString("\n") {
            if (it == 10) "CHANGED" else "line$it"
        })
        assertEquals(1, r.hunks.size)
        assertEquals(1, r.added)
        assertEquals(1, r.removed)
        // Context lines are included, so the hunk is bigger than the change.
        assertTrue(r.hunks.first().lines.size > 2)
        assertTrue(r.hunks.first().lines.size < 20)
    }

    @Test
    fun `changed lines carry their own 1-based numbers`() {
        // The bug this pins: numbers used to live only on the hunk header and were
        // taken from the start of the context window, so the header for a one-line
        // edit pointed three lines ABOVE the edit and there was no way to number
        // the added or removed line itself.
        val before = listOf("a", "b", "c", "d", "e", "f", "g", "h", "i", "j")
        val after = before.toMutableList().apply { set(4, "EDITED") }
        val h = d(before.joinToString("\n"), after.joinToString("\n")).hunks.single()

        val added = h.lines.first { it.kind == DiffLine.Kind.ADDED }
        val removed = h.lines.first { it.kind == DiffLine.Kind.REMOVED }

        // Index 4 is line 5 in both files.
        assertEquals(5, added.afterLine)
        assertEquals(5, removed.beforeLine)
        assertEquals("EDITED", added.text)
        assertEquals("e", removed.text)

        // An added line has no counterpart in the before-file, and vice versa.
        assertNull(added.beforeLine)
        assertNull(removed.afterLine)
    }

    @Test
    fun `the hunk header is the first line shown, context included`() {
        // git semantics: `@@ -3,7 +3,7 @@` starts at line 3, which is the first
        // CONTEXT line, not the changed line. Verified separately from the
        // per-line numbers because conflating the two is what caused the bug.
        val before = "one\ntwo\nthree\nfour\nfive\n"
        val after = "one\ntwo\nTHREE\nfour\nfive\n"
        val h = d(before, after).hunks.single()

        assertEquals("header starts at the first context line", 1, h.beforeStart)
        assertEquals(1, h.afterStart)
        // But the CHANGE is still line 3.
        assertEquals(3, h.changedAfterLines.single())
        assertEquals(3, h.changedBeforeLines.single())
    }

    @Test
    fun `context lines are numbered and every line either side of a change is present`() {
        val before = (1..20).joinToString("\n") { "l$it" }
        val after = (1..20).joinToString("\n") { if (it == 10) "EDIT" else "l$it" }
        val h = d(before, after).hunks.single()
        val ctx = h.lines.filter { it.kind == DiffLine.Kind.CONTEXT }
        assertEquals("three context lines either side", 6, ctx.size)
        assertEquals(listOf(7, 8, 9, 11, 12, 13), ctx.mapNotNull { it.afterLine }.sorted())
    }

    @Test
    fun `an added line is ADDED and its text is the new one`() {
        val before = "a\nb\nc\n"
        val after = "a\nINSERTED\nb\nc\n"
        val r = d(before, after)
        assertEquals(1, r.added)
        assertEquals(0, r.removed)
        val added = r.hunks.single().lines.filter { it.kind == DiffLine.Kind.ADDED }
        assertEquals(listOf("INSERTED"), added.map { it.text })
    }

    @Test
    fun `a brand new file is entirely added`() {
        val r = LineDiffer.diff("new.kt", "", "a\nb\n")
        assertTrue(r.created)
        assertEquals(2, r.added)
        assertEquals(0, r.removed)
        assertTrue(r.hunks.single().lines.all { it.kind == DiffLine.Kind.ADDED })
    }

    @Test
    fun `a deleted file is entirely removed`() {
        val r = LineDiffer.diff("gone.kt", "a\nb\n", "")
        assertTrue(r.deleted)
        assertEquals(0, r.added)
        assertEquals(2, r.removed)
    }

    @Test
    fun `two empty bodies are not a change`() {
        assertTrue(LineDiffer.diff("f.kt", "", "").unchanged)
    }

    @Test
    fun `adjacent edits share one hunk instead of shredding`() {
        // Four edits within a few lines must not become four fragments; a user
        // reviewing a diff wants to see the edit, not the fragmentation.
        val before = (1..40).joinToString("\n") { "l$it" }
        val after = (1..40).joinToString("\n") {
            if (it in 10..13) "EDIT$it" else "l$it"
        }
        val r = d(before, after)
        assertEquals(1, r.hunks.size)
        assertEquals(4, r.added)
        assertEquals(4, r.removed)
    }

    @Test
    fun `distant edits produce separate hunks`() {
        val before = (1..60).joinToString("\n") { "l$it" }
        val after = (1..60).joinToString("\n") {
            if (it == 5 || it == 55) "EDIT$it" else "l$it"
        }
        val r = d(before, after)
        assertEquals("edits 50 lines apart must not merge", 2, r.hunks.size)
    }

    @Test
    fun `the stat matches the hunk counts`() {
        val r = d((1..30).joinToString("\n") { "l$it" }, (1..30).joinToString("\n") {
            if (it == 15) "X" else "l$it"
        })
        val totalAdded = r.hunks.sumOf { it.added }
        val totalRemoved = r.hunks.sumOf { it.removed }
        assertEquals("+1 -1", r.stat())
        assertEquals(totalAdded, r.added)
        assertEquals(totalRemoved, r.removed)
    }

    @Test
    fun `a large changed region is labelled, not silently approximated`() {
        // THIS is the test that keeps the diff honest. Without tooLarge the UI
        // would render a coarse "+99999 -99999" as if it were a real diff.
        val before = (1..20_000).joinToString("\n") { "l$it" }
        val after = (1..20_000).joinToString("\n") { "l$it-X" }
        val r = d(before, after)
        assertTrue(r.tooLarge)
    }

    @Test
    fun `a large file with a small edit still diffs properly`() {
        // The cap must not punish size, only genuine bulk. This is why the trim
        // happens before the limit is checked.
        val before = (1..50_000).joinToString("\n") { "l$it" }
        val after = (1..50_000).joinToString("\n") { if (it == 25_000) "EDIT" else "l$it" }
        val r = d(before, after)
        assertFalse("a large file with a tiny edit should still diff", r.tooLarge)
        assertEquals(1, r.added)
        assertEquals(1, r.removed)
    }

    @Test
    fun `a whole-file rewrite is not labelled too large when within the cap`() {
        val before = (1..100).joinToString("\n") { "old$it" }
        val after = (1..100).joinToString("\n") { "new$it" }
        val r = d(before, after)
        assertFalse(r.tooLarge)
        assertEquals(100, r.added)
        assertEquals(100, r.removed)
    }

    @Test
    fun `an empty line is preserved rather than dropped`() {
        // Empty lines are real content; dropping them silently corrupts the diff.
        val r = d("a\n\nb", "a\n\nc")
        assertEquals(1, r.added)
        assertEquals(1, r.removed)
        assertTrue(r.hunks.single().lines.any { it.text == "" })
    }

    @Test
    fun `line counts are reported even when the file is unchanged`() {
        val r = d("a\nb\nc", "a\nb\nc")
        assertEquals(3, r.beforeLineCount)
        assertEquals(3, r.afterLineCount)
    }

    @Test
    fun `reordering lines is reported as removals and additions`() {
        val r = d("a\nb\nc", "c\nb\na")
        assertEquals(2, r.added)
        assertEquals(2, r.removed)
        assertTrue(r.hasChanges)
    }
}
