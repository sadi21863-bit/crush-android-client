package com.crush.mobile.domain.file

/**
 * One line of a diff, carrying its own 1-based line numbers.
 *
 * WHY EVERY LINE CARRIES ITS NUMBER
 *
 * The first version stored the numbers only on the hunk, taken from the first
 * line of the CONTEXT WINDOW - so `beforeStart` for a one-line edit was the line
 * three lines above the edit, and there was no way to number the actual added and
 * removed lines. A diff whose numbers point at the wrong places is worse than
 * none: the user jumps to line 5 and lands on line 2.
 *
 * [beforeLine] is null on added lines and [afterLine] is null on removed ones,
 * which is the only honest representation - there is no such line in that file.
 */
data class DiffLine(
    val kind: Kind,
    val text: String,
    /** 1-based line in the before-file, or null when this line was added. */
    val beforeLine: Int?,
    /** 1-based line in the after-file, or null when this line was removed. */
    val afterLine: Int?
) {
    enum class Kind { CONTEXT, ADDED, REMOVED }
}

/**
 * A contiguous changed region with surrounding context.
 *
 * Hunking is what makes a diff readable: a 200-line file with one changed line
 * is not a 200-line red/green slab. Showing only the changed regions with a few
 * lines of context is the difference between a diff a user reviews and one they
 * close without reading.
 *
 * [beforeStart] / [afterStart] are the first line SHOWN, context included, which
 * is what `git diff` and every editor put in a `@@ ... @@` header.
 */
data class DiffHunk(
    val beforeStart: Int,
    val afterStart: Int,
    val lines: List<DiffLine>
) {
    val added: Int get() = lines.count { it.kind == DiffLine.Kind.ADDED }
    val removed: Int get() = lines.count { it.kind == DiffLine.Kind.REMOVED }

    /** The actual changed line numbers, not the context around them. */
    val changedBeforeLines: List<Int>
        get() = lines.filter { it.kind == DiffLine.Kind.REMOVED }.mapNotNull { it.beforeLine }
    val changedAfterLines: List<Int>
        get() = lines.filter { it.kind == DiffLine.Kind.ADDED }.mapNotNull { it.afterLine }
}

/**
 * The computed diff for one file.
 *
 * WHY [tooLarge] IS NOT A FAILURE
 *
 * The engine gives us the list of files a session touched, and we read them off
 * the app-private workspace ourselves. There is no diff endpoint, so the diff is
 * ours to compute - and for a very large changed region the honest answer is
 * "too big to show line by line" rather than a truncated slab that looks
 * complete. A partial diff presented as a whole one is the same lie as a stop
 * that was never confirmed.
 */
data class FileDiff(
    val path: String,
    val hunks: List<DiffHunk>,
    val added: Int,
    val removed: Int,
    val beforeLineCount: Int,
    val afterLineCount: Int,
    val created: Boolean,
    val deleted: Boolean,
    val tooLarge: Boolean = false,
    val unreadable: Boolean = false,
    val unchanged: Boolean = false
) {
    val hasChanges: Boolean get() = added > 0 || removed > 0 || created || deleted

    /** e.g. "+12 -4". Shown on the collapsed row so change size is scannable. */
    fun stat(): String = "+$added -$removed"
}

/** Internal: one diff op with the source line numbers attached. */
private data class Op(
    val kind: DiffLine.Kind,
    val text: String,
    /** 0-based index in the before-list, or null for added lines. */
    val before: Int?,
    /** 0-based index in the after-list, or null for removed lines. */
    val after: Int?
)

object LineDiffer {

    /**
     * Lines in the CHANGED REGION above which we refuse to compute a line diff.
     *
     * 10k lines DP'd against each other is 100M cells. The point is to be correct
     * and fast for real edits, not to do the whole thing. This is checked after
     * trimming the common prefix and suffix, so a large file with a small edit
     * still diffs fine - the cap only bites when the changed region itself is
     * enormous.
     */
    private const val MAX_DIFF_LINES = 10_000

    /** Absolute per-file cap, so a pathological file cannot stall a turn. */
    private const val MAX_LINES_PER_FILE = 100_000

    private const val CONTEXT = 3

    /** Diffs two file bodies. */
    fun diff(path: String, before: String, after: String): FileDiff {
        if (before.isEmpty() && after.isEmpty()) {
            return FileDiff(path, emptyList(), 0, 0, 0, 0, false, false, unchanged = true)
        }
        val b = before.splitIntoLines()
        val a = after.splitIntoLines()

        if (b.isEmpty()) {
            // Brand new file: every line is added, no "before" to compare against.
            return FileDiff(
                path = path,
                hunks = wholeFileHunk(DiffLine.Kind.ADDED, a),
                added = a.size,
                removed = 0,
                beforeLineCount = 0,
                afterLineCount = a.size,
                created = true,
                deleted = false
            )
        }
        if (a.isEmpty()) {
            return FileDiff(
                path = path,
                hunks = wholeFileHunk(DiffLine.Kind.REMOVED, b),
                added = 0,
                removed = b.size,
                beforeLineCount = b.size,
                afterLineCount = 0,
                created = false,
                deleted = true
            )
        }

        // Trim the common prefix and suffix first. A one-line edit in a 5000-line
        // file should not cost a 25M-cell DP, and with trimming it costs about 1.
        var p = 0
        while (p < b.size && p < a.size && b[p] == a[p]) p++

        var s = 0
        val maxSuffix = minOf(b.size - p, a.size - p)
        while (s < maxSuffix && b[b.size - 1 - s] == a[a.size - 1 - s]) s++

        val midB = b.subList(p, b.size - s)
        val midA = a.subList(p, a.size - s)

        if (midB.isEmpty() && midA.isEmpty()) {
            return FileDiff(path, emptyList(), 0, 0, b.size, a.size, false, false, unchanged = true)
        }

        val tooLarge =
            (midB.size > MAX_DIFF_LINES || midA.size > MAX_DIFF_LINES ||
                b.size > MAX_LINES_PER_FILE || a.size > MAX_LINES_PER_FILE)

        val ops = if (tooLarge) {
            // Coarse ops: no LCS, just "these line counts differ". Paired with
            // tooLarge so the UI says it is an approximation.
            buildList {
                repeat(midB.size) { add(Op(DiffLine.Kind.REMOVED, midB[it], p + it, null)) }
                repeat(midA.size) { add(Op(DiffLine.Kind.ADDED, midA[it], null, p + it)) }
            }
        } else {
            lcsOps(midB.toTypedArray(), midA.toTypedArray()).map { op ->
                // Shift back to full-file indices. Without this, hunk numbers were
                // relative to the trimmed middle, so a change on line 10 was
                // reported as line 1 - off by the length of the common prefix.
                op.copy(before = op.before?.plus(p), after = op.after?.plus(p))
            }
        }

        // Re-attach the trimmed prefix and suffix as CONTEXT ops. Without this the
        // trim removes exactly the lines a hunk needs around a change, so a
        // one-line edit in a 20-line file renders as two bare lines with no
        // context - and an empty line inside the region disappears entirely.
        val prefixOps = (0 until p).map { Op(DiffLine.Kind.CONTEXT, b[it], it, it) }
        val suffixOps = (0 until s).map {
            Op(
                DiffLine.Kind.CONTEXT,
                b[b.size - s + it],
                b.size - s + it,
                a.size - s + it
            )
        }
        val fullOps = prefixOps + ops + suffixOps

        val hunks = hunk(fullOps)
        return FileDiff(
            path = path,
            hunks = hunks,
            added = hunks.sumOf { it.added },
            removed = hunks.sumOf { it.removed },
            beforeLineCount = b.size,
            afterLineCount = a.size,
            created = false,
            deleted = false,
            tooLarge = tooLarge
        )
    }

    private fun wholeFileHunk(kind: DiffLine.Kind, lines: List<String>): List<DiffHunk> {
        if (lines.isEmpty()) return emptyList()
        val numbered = lines.mapIndexed { i, text ->
            when (kind) {
                DiffLine.Kind.ADDED -> DiffLine(kind, text, null, i + 1)
                else -> DiffLine(kind, text, i + 1, null)
            }
        }
        return listOf(DiffHunk(numbered.first().beforeLine ?: 0, numbered.first().afterLine ?: 0, numbered))
    }

    /** Splits into lines, dropping a single trailing newline so "a\n" == "a". */
    private fun String.splitIntoLines(): List<String> {
        if (this.isEmpty()) return emptyList()
        val s = if (endsWith("\n")) dropLast(1) else this
        return if (s.isEmpty()) listOf("") else s.split("\n")
    }

    // ---- LCS ----------------------------------------------------------------

    /**
     * Dynamic-programming LCS over lines, returned as ops with line numbers.
     *
     * Memory is O(n*m), which is exactly why the prefix/suffix trim and
     * [MAX_DIFF_LINES] exist: with the trim in place the middle region is small
     * for essentially every real edit.
     */
    private fun lcsOps(a: Array<String>, b: Array<String>): List<Op> {
        val n = a.size
        val m = b.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1
                else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
        val out = ArrayList<Op>(n + m)
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> {
                    out.add(Op(DiffLine.Kind.CONTEXT, a[i], i, j)); i++; j++
                }
                dp[i + 1][j] >= dp[i][j + 1] -> {
                    out.add(Op(DiffLine.Kind.REMOVED, a[i], i, null)); i++
                }
                else -> {
                    out.add(Op(DiffLine.Kind.ADDED, b[j], null, j)); j++
                }
            }
        }
        while (i < n) {
            out.add(Op(DiffLine.Kind.REMOVED, a[i], i, null)); i++
        }
        while (j < m) {
            out.add(Op(DiffLine.Kind.ADDED, b[j], null, j)); j++
        }
        return out
    }

    // ---- hunking ------------------------------------------------------------

    /**
     * Groups ops into hunks of changes with [CONTEXT] unchanged lines either side.
     *
     * The hunk header is the first line SHOWN, context included - `git diff`
     * semantics. The per-line numbers, which is what the UI actually renders, are
     * carried on each [DiffLine] rather than derived from the header, so a user
     * clicking a number lands on that line and not three lines above it.
     */
    private fun hunk(ops: List<Op>): List<DiffHunk> {
        val changedAt = ops.withIndex()
            .filter { it.value.kind != DiffLine.Kind.CONTEXT }
            .map { it.index }
        if (changedAt.isEmpty()) return emptyList()

        // Merge change runs that sit within 2*CONTEXT of each other, so a
        // scattered edit does not shred into five near-identical fragments.
        val ranges = mutableListOf<IntRange>()
        var start = changedAt.first()
        var end = start
        for (idx in changedAt.drop(1)) {
            if (idx - end <= CONTEXT * 2) {
                end = idx
            } else {
                ranges.add(start..end); start = idx; end = idx
            }
        }
        ranges.add(start..end)

        return ranges.map { r ->
            val from = maxOf(0, r.first - CONTEXT)
            val to = minOf(ops.lastIndex, r.last + CONTEXT)
            val slice = ops.subList(from, to + 1)
            val lines = slice.map { op ->
                DiffLine(
                    kind = op.kind,
                    text = op.text,
                    // 0-based internally, 1-based on the wire to the UI.
                    beforeLine = op.before?.plus(1),
                    afterLine = op.after?.plus(1)
                )
            }
            DiffHunk(
                beforeStart = lines.firstNotNullOfOrNull { it.beforeLine } ?: 0,
                afterStart = lines.firstNotNullOfOrNull { it.afterLine } ?: 0,
                lines = lines
            )
        }
    }
}
