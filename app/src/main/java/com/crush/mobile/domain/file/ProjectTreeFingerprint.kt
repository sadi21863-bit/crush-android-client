package com.crush.mobile.domain.file

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The P6 Batch F project-tree fingerprint (build plan
 * `build-plan-handoff-p6-2026-11-08` §8 f1): a deterministic STAT-ONLY walk
 * of a project folder, hashed - "what existed at handoff", the machine
 * anchor carried by the verify-first first turn and stored on the row, and
 * the thing a later app-side re-walk can compare byte-for-byte (the
 * agent's own verify-turn walk stays semantic - it compares tree reality
 * against the APPROVED SUMMARY, not against somebody's digest).
 *
 * RECORDS: per file, the relative path (forward slashes, the
 * [File.invariantSeparatorsPath] discipline WorkspaceSnapshotter uses),
 * the byte size and the mtimeMs. The record rows are sorted
 * lexicographically after the walk (a unique path is the row's natural
 * prefix, so this is plan §8's "sorted by path"); the SHA-256 lowercase
 * hex is computed over the concatenated row stream - the identical hex
 * discipline ProjectTrustService.currentFingerprint keeps.
 *
 * EXCLUSIONS + BOUNDS: the WorkspaceSnapshotter noise rule rides
 * (SKIP_DIRS - .git, build, node_modules and friends, which churn on every
 * build/commit and would make mid-work fingerprints flip uselessly), and
 * its depth cap (6, "comfortably covers a source tree while refusing to
 * walk into a node_modules the agent happened to create"). What lies
 * beyond those bounds is invisible to the digest exactly as it is to the
 * diff surface - part of the SAME accepted false-negative family the
 * snapshotter documents, one line of it stated plan-verbatim: a
 * same-size rewrite landing inside the filesystem's mtime resolution is
 * DOCUMENTED, not discriminated (stat-only can never report a phantom,
 * and the verify turn's semantic re-walk is the catch where it matters).
 *
 * EXPLICIT MARKERS over silent skips (the snapshotter's ethos, plan f1):
 * - a file the walk SEES but cannot read-stat records a
 *   `<path><TAB>?<TAB>?` row (never silent, never invented numbers);
 * - a DIRECTORY the walk refuses (unreadable, or one whose listing
 *   vanished mid-walk) records its own `<path>/<TAB>?<TAB>?` row and its
 *   subtree is not visited. A row vanishing flips the digest by
 *   absence, a row appearing by presence, and an unreadable thing that
 *   stays unreadable stays deterministic across two walks (f2).
 *
 * MISSING ROOT: an absent or non-directory root is the empty record set -
 * still a deterministic digest ("nothing existed"), never an error. The
 * "(not recorded)" degradation stays owned by a walk that could not RUN,
 * handled fail-soft by the dispatch caller, not by this walk.
 */
object ProjectTreeFingerprint {

    /** The WorkspaceSnapshotter's noise rule, byte-identical to its set. */
    private val SKIP_DIRS = setOf(
        ".git", "build", "node_modules", ".gradle", "__pycache__",
        ".idea", "dist", "out", "target"
    )
    private const val MAX_DEPTH = 6

    /**
     * The digest of [root]'s current stat state. The whole walk hops to
     * Dispatchers.IO (the ProjectTrustService.gate shape) - safe to call
     * from the main thread, and cheap: stat-only, no file contents read
     * (plan f1, unlike WorkspaceSnapshotter's content capture).
     */
    suspend fun of(root: File): String = withContext(Dispatchers.IO) {
        digestOf(recordsOf(root))
    }

    /**
     * The sorted record stream: one newline-terminated row per walked file
     * (or explicit marker). [canRead] is the walk's unreadability hint -
     * the real [File.canRead] by default, injectable for the marker tests
     * (Windows cannot deny-read a temp file portably; the honest test
     * injects the refusal instead of pretending the branch ran).
     */
    internal fun recordsOf(root: File, canRead: (File) -> Boolean = File::canRead): List<String> {
        if (!root.exists() || !root.isDirectory) return emptyList()
        val rows = mutableListOf<String>()
        fun walk(dir: File, depth: Int) {
            if (depth > MAX_DEPTH) return
            val kids = runCatching { dir.listFiles() }.getOrNull()
            if (kids == null) {
                // listFiles refused (an unreadable directory, or it vanished
                // mid-walk): the directory's own explicit marker row.
                rows.add("${dir.relativeTo(root).invariantSeparatorsPath}/\t?\t?\n")
                return
            }
            for (child in kids.sortedBy { it.name }) {
                val relative = child.relativeTo(root).invariantSeparatorsPath
                if (child.isDirectory) {
                    // Skip build artefacts and dependency trees - the same
                    // noise rule the snapshotter walks with.
                    if (child.name in SKIP_DIRS) continue
                    if (!canRead(child)) {
                        // The directory's CONTENTS are refused: an explicit
                        // marker row - the subtree is not visited, and its
                        // presence/absence still moves the digest.
                        rows.add("$relative/\t?\t?\n")
                        continue
                    }
                    walk(child, depth + 1)
                    continue
                }
                if (!child.isFile) continue
                if (!canRead(child)) {
                    // Seen, but the stat side refused: the explicit marker
                    // row, hashed like any other record.
                    rows.add("$relative\t?\t?\n")
                    continue
                }
                rows.add("$relative\t${child.length()}\t${child.lastModified()}\n")
            }
        }
        walk(root, 0)
        return rows.sorted()
    }

    /**
     * SHA-256 lowercase hex over the UTF-8 bytes of the concatenated record
     * stream - the same hex discipline ProjectTrustService.currentFingerprint
     * keeps (chunked MessageDigest, no intermediate mega-strings).
     */
    internal fun digestOf(rows: List<String>): String {
        val md = MessageDigest.getInstance("SHA-256")
        rows.forEach { row -> md.update(row.toByteArray(Charsets.UTF_8)) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
