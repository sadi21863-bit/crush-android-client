package com.opencode.chat.domain.file

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
 * so [WorkspaceSnapshotter] only re-reads files whose size or mtime changed
 * since the last snapshot.
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
    /** path -> the file's content when the turn started. */
    data class Snapshot(
        val contents: Map<String, String>,
        val truncatedPaths: Set<String>,
        val capturedAtMs: Long
    ) {
        fun before(path: String): String? = contents[path]
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
                if (previous != null && previous.contents.containsKey(key)) {
                    // Unchanged since the last snapshot: keep the same content
                    // without paying for a re-read.
                    contents[key] = previous.contents.getValue(key)
                    continue
                }

                val text = runCatching {
                    if (looksBinary(child)) null
                    else child.readText()
                }.getOrNull()

                if (text == null || text.length > maxFileBytes) {
                    truncated.add(key)
                } else {
                    contents[key] = text
                }

                if (totalBytesOf(contents) > maxTotalBytes) {
                    // Over the total budget: stop storing content and mark the
                    // rest as truncated rather than dropping them entirely.
                    val over = totalBytesOf(contents) - maxTotalBytes
                    if (over > 0) {
                        truncated.add(key)
                        contents.remove(key)
                    }
                }
            }
        }

        walk(root, 0)
        return Snapshot(contents, truncated, System.currentTimeMillis())
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
