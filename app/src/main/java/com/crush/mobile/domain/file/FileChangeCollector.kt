package com.crush.mobile.domain.file

import java.io.File

/**
 * Joins the three sources that make up a file-change view.
 *
 * The engine's file list alone is not enough: it names files but carries no
 * before-image, so showing it directly would render every touched file as all
 * additions. The snapshot is taken before the prompt, the file list is the
 * engine's account of what it touched, and the live file is the after-image. All
 * three together are the diff.
 *
 * Any file the engine names but we cannot produce a real before/after for is
 * reported in [FileChangeSet.unreadable] instead of being silently omitted. A
 * change the user cannot see is the bug this whole feature exists to fix.
 */
class FileChangeCollector(
    private val snapshotter: WorkspaceSnapshotter = WorkspaceSnapshotter()
) {

    fun captureBefore(root: File, previous: WorkspaceSnapshotter.Snapshot? = null) =
        snapshotter.capture(root, previous)

    /**
     * @param workspace the app-private workspace root.
     * @param tracked    paths from the engine's filetracker, relative to [workspace].
     */
    fun collect(
        workspace: File,
        tracked: List<String>,
        before: WorkspaceSnapshotter.Snapshot?
    ): FileChangeSet {
        if (before == null) {
            // No pre-turn snapshot means no before-image. Saying so is the honest
            // option: rendering one-sided diffs would blame the agent for changes
            // it never made, which is exactly the class of lie this feature was
            // written to stop.
            return FileChangeSet(
                sessionId = null,
                diffs = emptyList(),
                unreadable = emptyList(),
                snapshotUnavailable = true,
                capturedAtMs = System.currentTimeMillis()
            )
        }

        val diffs = ArrayList<FileDiff>()
        val unreadable = ArrayList<String>()

        // Normalise FIRST, then dedupe. Deduping the raw strings left "a.kt" and
        // "./a.kt" as two entries, and the same file rendered twice - once as its
        // real edit and once as an identical duplicate, which reads to a user as
        // the agent having made the same change twice.
        val targets = tracked
            .mapNotNull { raw -> normalise(raw)?.let { it to raw } }
            .distinctBy { it.first }

        for ((rel, rawPath) in targets) {
            val file = File(workspace, rel)

            // Refuse to escape the workspace. A path with ".." in it is not a file
            // we should be reading on the agent's word.
            if (!isInside(workspace, file)) {
                unreadable.add(rawPath)
                continue
            }

            val beforeText = before.before(rel)

            if (!file.exists()) {
                if (beforeText == null) {
                    // Named by the engine, never seen by us: report it rather than
                    // dropping it.
                    unreadable.add(rawPath)
                    continue
                }
                diffs.add(LineDiffer.diff(rel, beforeText, ""))
                continue
            }

            if (beforeText == null) {
                if (rel in before.truncatedPaths) {
                    unreadable.add(rawPath)
                    continue
                }
                // New file: the agent created it after our snapshot.
                val after = readOrNull(file, snapshotter)
                if (after == null) {
                    unreadable.add(rawPath)
                } else {
                    diffs.add(LineDiffer.diff(rel, "", after))
                }
                continue
            }

            val after = readOrNull(file, snapshotter)
            if (after == null) {
                unreadable.add(rawPath)
            } else {
                diffs.add(LineDiffer.diff(rel, beforeText, after))
            }
        }

        return FileChangeSet(
            sessionId = null,
            diffs = diffs.filter { it.hasChanges },
            unreadable = unreadable,
            snapshotUnavailable = false,
            capturedAtMs = before.capturedAtMs
        )
    }

    /** Unreadable, binary, or over budget - in every case we cannot diff it. */
    private fun readOrNull(file: File, snapshotter: WorkspaceSnapshotter): String? =
        runCatching {
            if (file.length() > snapshotter.maxFileBytes) return null
            file.readText()
        }.getOrNull()

    private fun normalise(raw: String): String? {
        if (raw.isBlank()) return null
        return raw.replace('\\', '/').removePrefix("./").ifBlank { null }
    }

    /**
     * Canonical-path containment. Comparing strings would be defeated by
     * `sub/../../etc/passwd`, and by a symlink that happens to point outside.
     */
    private fun isInside(root: File, candidate: File): Boolean = runCatching {
        val rootPath = root.canonicalPath
        val candPath = candidate.canonicalPath
        candPath == rootPath || candPath.startsWith(rootPath + File.separator)
    }.getOrDefault(false)
}
