package com.crush.mobile.domain.file

import java.io.File
import java.security.MessageDigest

/**
 * Captures a workspace before a turn, so there is something real to diff against.
 *
 * WHY SNAPSHOT BEFORE THE PROMPT
 *
 * The engine tells us which files a session touched, but only after the fact. To
 * show a diff you need the file as it was BEFORE the agent got to it, and the
 * only moment that exists is just before the prompt is sent. Snapshotting after
 * the turn gives you the after-image twice, and every file would render as
 * "+24 -0" - which looks like the agent rewrote the whole file and tells the user
 * nothing about what actually happened.
 *
 * WHY IT IS CAPSIZED
 *
 * The workspace is app-private but not necessarily small, and a coding agent can
 * easily generate a build artefact or a large data file inside it. Holding
 * unbounded file contents in a ViewModel on a 0.7–1.0 GB free device is how you
 * invite the OOM the chaos scenarios are supposed to catch. So there are per-file
 * and total budgets, and anything over budget is recorded as a "too big to
 * snapshot" marker rather than silently stored or silently skipped.
 *
 * FILES ARE READ ONCE. Reading a 2MB file on every prompt would be a real cost,
 * so [WorkspaceSnapshotter] stores each captured file's size and mtime and
 * reuses the previous content only when BOTH still match the file on disk.
 *
 * The reuse guard is load-bearing, not an optimisation footnote: the first
 * version keyed reuse on path PRESENCE alone, so turn N's before-image was
 * silently turn N-1's state. An edit made between turns (by the agent's own
 * earlier work, or by the user in another app) then rendered as an inflated
 * diff, with outside edits attributed to the agent at this turn. Matching on
 * size+mtime is the same guard any build system uses; its known miss - a
 * same-size rewrite landing inside the filesystem's mtime resolution - is a
 * cheap, rare false-negative set against re-reading every file on every prompt.
 */
class WorkspaceSnapshotter(
    /**
     * Largest file we will read into memory. Public because
     * [FileChangeCollector] applies the same ceiling when reading the
     * after-image, so the two halves of a diff are held to identical limits - a
     * snapshot taken at one budget and read back at another would report phantom
     * differences.
     */
    val maxFileBytes: Int = 256 * 1024,
    val maxTotalBytes: Int = 4 * 1024 * 1024,
    val maxFiles: Int = 400
) {
    /**
     * path -> the file's content when the turn started, plus the identity the
     * content was read against (see [Stat]).
     *
     * [contents] and [before()] are the API [FileChangeCollector] consumes and
     * are deliberately unchanged by the staleness fix; [stats] rides along so
     * the NEXT capture can decide whether [contents] still describes the file
     * on disk rather than a file that has since been edited.
     */
    data class Snapshot(
        val contents: Map<String, String>,
        val truncatedPaths: Set<String>,
        val capturedAtMs: Long,
        /** path -> size+mtime at the moment [contents] was read. */
        val stats: Map<String, Stat> = emptyMap()
    ) {
        fun before(path: String): String? = contents[path]

        /**
         * The file identity content was read against. Length is the on-disk
         * byte size, not the character count - utf-8 multi-byte files have
         * fewer chars than bytes, and it is the FILE that changes identity.
         */
        data class Stat(val lastModified: Long, val length: Long)
    }

    /**
     * Reads [root] shallowly, following no symlinks and descending no deeper than
     * about four directories, which comfortably covers a source tree while
     * refusing to walk into a `node_modules` the agent happened to create.
     */
    fun capture(root: File, previous: Snapshot? = null): Snapshot {
        if (!root.exists() || !root.isDirectory) {
            // emptyMap<...>() cannot infer from the parameter list alone here.
            return Snapshot(emptyMap<String, String>(), emptySet(), System.currentTimeMillis())
        }

        val contents = LinkedHashMap<String, String>()
        val truncated = LinkedHashSet<String>()
        val stats = LinkedHashMap<String, Snapshot.Stat>()

        fun walk(dir: File, depth: Int) {
            if (depth > MAX_DEPTH || contents.size + truncated.size >= maxFiles) return
            val kids = runCatching { dir.listFiles() }.getOrNull() ?: return
            for (child in kids.sortedBy { it.name }) {
                if (contents.size + truncated.size >= maxFiles) return
                val key = child.relativeTo(root).invariantSeparatorsPath

                if (child.isDirectory) {
                    // Skip build artefacts and dependency trees; they are noise
                    // and the dominant source of large files.
                    if (child.name in SKIP_DIRS) continue
                    walk(child, depth + 1)
                    continue
                }
                if (!child.isFile) continue

                val len = child.length()
                if (len > maxFileBytes) {
                    // Recorded, not stored. A file we could not snapshot still has
                    // to show as "too large to diff" rather than as an all-added
                    // file, which is what a missing before-image looks like.
                    truncated.add(key)
                    continue
                }
                val stat = Snapshot.Stat(child.lastModified(), len)
                if (previous != null) {
                    val prevText = previous.contents[key]
                    if (prevText != null && previous.stats[key] == stat) {
                        // Size AND mtime both unchanged since the last snapshot:
                        // the cached content still describes this file, so keep
                        // it without paying for a re-read.
                        contents[key] = prevText
                        stats[key] = stat
                        continue
                    }
                    // Stale or unseen: fall through and re-read. A presence-only
                    // check used to keep the previous turn's content here, which
                    // is what made second edits render as inflated diffs.
                }

                val text = runCatching {
                    if (looksBinary(child)) null
                    else child.readText()
                }.getOrNull()

                if (text == null || text.length > maxFileBytes) {
                    truncated.add(key)
                } else {
                    contents[key] = text
                    stats[key] = stat
                }

                if (totalBytesOf(contents) > maxTotalBytes) {
                    // Over the total budget: stop storing content and mark the
                    // rest as truncated rather than dropping them entirely.
                    val over = totalBytesOf(contents) - maxTotalBytes
                    if (over > 0) {
                        truncated.add(key)
                        contents.remove(key)
                        // Keep the stat map consistent with contents: a reused
                        // stat whose content is gone would be a landmine.
                        stats.remove(key)
                    }
                }
            }
        }

        walk(root, 0)
        return Snapshot(contents, truncated, System.currentTimeMillis(), stats)
    }

    private fun totalBytesOf(m: Map<String, String>): Long =
        m.values.sumOf { it.length.toLong() }

    /**
     * Detects a binary file by whether it contains a NUL byte, which is the one
     * test that does not depend on the encoding or the file type.
     *
     * The first version searched for a SPACE character and would have labelled
     * every ordinary source file binary - a check that passes for the wrong reason
     * is still broken, and here it would have suppressed every diff.
     */
    private fun looksBinary(f: File): Boolean {
        if (f.length() == 0L) return false
        return runCatching {
            val bytes = f.inputStream().use { it.readNBytes(512) }
            bytes.any { it == 0.toByte() }
        }.getOrDefault(true)
    }

    companion object {
        private const val MAX_DEPTH = 6
        private val SKIP_DIRS = setOf(
            ".git", "build", "node_modules", ".gradle", "__pycache__",
            ".idea", "dist", "out", "target"
        )
    }
}
