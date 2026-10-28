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
     *
     * Since the LCS pass went linear-memory the cap no longer guards memory
     * (that is O(n+m) now); it guards TIME, which is still O(n*m) for the
     * middle region.
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
     * Minimal LCS edit script over lines, returned as ops with line numbers.
     *
     * WHY THERE IS NO FULL DP TABLE (2026-10-27)
     *
     * The first version allocated `Array(n+1) { IntArray(m+1) }` and held it for
     * the whole reconstruction. At [MAX_DIFF_LINES] that is 100M ints - about
     * 400MB on this app's ~1GB-heap device - so the cap bounded the CELL COUNT
     * while the table itself stayed unbounded in the two dimensions it was
     * meant to guard. The fix is the classic one: keep only two rows of lengths
     * at a time (~80KB at the cap), and reconstruct the actual edit script with
     * Hirschberg's divide-and-conquer - split the before side at its midpoint,
     * pick the after-side split that maximises LCS(left, y-pref) +
     * LCS(right, y-suff) by combining a forward lengths pass over the first
     * half with a backward pass over the second, and recurse into both halves.
     * Total time stays O(n*m), same as the old table; peak memory drops to
     * O(n+m) per live recursion level and the levels are transient.
     *
     * WHAT CAN DIFFER FROM THE OLD BACKTRACE: when several minimal scripts
     * exist (repeated lines, block moves), this one is not guaranteed to pick
     * the tie the old forward walk picked. EVERY minimal script has the same
     * LCS length, so added/removed counts, hunk merging and the tooLarge path
     * are all unchanged; only which duplicated line a hunk lines up as its
     * anchor can differ, and any of those scripts is a correct minimal diff.
     */
    private fun lcsOps(before: Array<String>, after: Array<String>): List<Op> {
        val out = ArrayList<Op>(before.size + after.size)
        reconstruct(before, 0, before.size, after, 0, after.size, out)
        return out
    }

    /**
     * Appends the ops for window x[x0 until x1) vs y[y0 until y1).
     *
     * Indices stay GLOBAL to the arrays on purpose: op line numbers are the
     * original files', so per-window renumbering would have shifted every hunk
     * after the first recursion level.
     *
     * Tie-break in the split below takes the FIRST maximum, which keeps block
     * moves reading as "removed here, added there" rather than shuffling the
     * y split point to the far end.
     */
    private fun reconstruct(
        x: Array<String>, x0: Int, x1: Int,
        y: Array<String>, y0: Int, y1: Int,
        out: MutableList<Op>
    ) {
        val n = x1 - x0
        val m = y1 - y0
        when {
            n == 0 ->
                // Nothing left to keep on the before side: the rest is additions.
                for (j in y0 until y1) out.add(Op(DiffLine.Kind.ADDED, y[j], null, j))
            m == 0 ->
                for (i in x0 until x1) out.add(Op(DiffLine.Kind.REMOVED, x[i], i, null))
            n == 1 -> {
                // Single before line: match it against its first counterpart in
                // the after window, additions otherwise falling in file order.
                val line = x[x0]
                val firstMatch = (y0 until y1).firstOrNull { y[it] == line }
                if (firstMatch == null) {
                    out.add(Op(DiffLine.Kind.REMOVED, line, x0, null))
                    for (j in y0 until y1) out.add(Op(DiffLine.Kind.ADDED, y[j], null, j))
                } else {
                    for (j in y0 until firstMatch) {
                        out.add(Op(DiffLine.Kind.ADDED, y[j], null, j))
                    }
                    out.add(Op(DiffLine.Kind.CONTEXT, line, x0, firstMatch))
                    for (j in firstMatch + 1 until y1) {
                        out.add(Op(DiffLine.Kind.ADDED, y[j], null, j))
                    }
                }
            }
            else -> {
                val xmid = x0 + n / 2
                // forward[k] = LCS(x[x0..xmid), y[y0..y0+k))
                val forward = lcsLengths(x, x0, xmid, y, y0, y1)
                // backward[k] = LCS(x[xmid..x1), y[y0+k..y1))
                val backward = lcsLengthsBackward(x, xmid, x1, y, y0, y1)
                var best = -1
                var bestK = 0
                for (k in 0..(y1 - y0)) {
                    val total = forward[k] + backward[k]
                    if (total > best) {
                        best = total
                        bestK = k
                    }
                }
                val ymid = y0 + bestK
                reconstruct(x, x0, xmid, y, y0, ymid, out)
                reconstruct(x, xmid, x1, y, ymid, y1, out)
            }
        }
    }

    /**
     * LCS lengths of the x prefix [x0 until xEnd) against every prefix of the
     * y window; result[k] = LCS(x[x0..xEnd), y[y0..y0+k)).
     *
     * Two rolling rows and no table. Buffer reuse works because row rendering
     * is left-to-right: `cur[k-1]` is always either this row's fresh value or
     * the 0 seed at index 0, never a stale value from two rows ago, so the
     * swapped buffer needs no re-zeroing.
     */
    private fun lcsLengths(
        x: Array<String>, x0: Int, xEnd: Int,
        y: Array<String>, y0: Int, y1: Int
    ): IntArray {
        val w = y1 - y0
        var prev = IntArray(w + 1)
        var cur = IntArray(w + 1)
        for (i in x0 until xEnd) {
            for (k in 1..w) {
                cur[k] = if (x[i] == y[y0 + k - 1]) prev[k - 1] + 1
                else maxOf(prev[k], cur[k - 1])
            }
            val swap = prev
            prev = cur
            cur = swap
        }
        return prev
    }

    /**
     * Mirror of [lcsLengths] over the x suffix [xStart until x1); result[k] =
     * LCS(x[xStart..x1), y[y0+k..y1)). Rendered right-to-left for the same
     * buffer-reuse reason, with index `w` at each row staying the 0 seed.
     */
    private fun lcsLengthsBackward(
        x: Array<String>, xStart: Int, x1: Int,
        y: Array<String>, y0: Int, y1: Int
    ): IntArray {
        val w = y1 - y0
        var prev = IntArray(w + 1)
        var cur = IntArray(w + 1)
        for (i in x1 - 1 downTo xStart) {
            for (k in w - 1 downTo 0) {
                cur[k] = if (x[i] == y[y0 + k]) prev[k + 1] + 1
                else maxOf(prev[k], cur[k + 1])
            }
            val swap = prev
            prev = cur
            cur = swap
        }
        return prev
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
