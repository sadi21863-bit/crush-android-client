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
     * One turn's changes plus the after-images the diff was formed from.
     *
     * The Review surface needs the after-images to keep its revert guard
     * honest ("the file must still read as the turn left it"), and building
     * them by re-reading the files a second time would measure a different
     * instant than the diff describes. Both come from ONE read, here.
     */
    data class CollectedChanges(
        val changes: FileChangeSet,
        /**
         * Collect-time text per RENDERED diff path, as [readOrNull] saw it:
         * non-null for a file that existed at collect time, null for a path
         * the turn deleted. Entries exist only for rendered diffs.
         */
        val afterTexts: Map<String, String?>
    )

    fun collect(
        workspace: File,
        tracked: List<String>,
        before: WorkspaceSnapshotter.Snapshot?
    ): FileChangeSet = collectFull(workspace, tracked, before).changes

    /**
     * @param workspace the app-private workspace root.
     * @param tracked    paths from the engine's filetracker, relative to
     * [workspace]. Absolute paths that already sit under the workspace are
     * reduced to their relative form; absolute paths from anywhere else are
     * rejected as undiffable (see [normalise]) rather than rendered.
     */
    fun collectFull(
        workspace: File,
        tracked: List<String>,
        before: WorkspaceSnapshotter.Snapshot?
    ): CollectedChanges {
        if (before == null) {
            // No pre-turn snapshot means no before-image. Saying so is the honest
            // option: rendering one-sided diffs would blame the agent for changes
            // it never made, which is exactly the class of lie this feature was
            // written to stop.
            return CollectedChanges(
                FileChangeSet(
                    sessionId = null,
                    diffs = emptyList(),
                    unreadable = emptyList(),
                    snapshotUnavailable = true,
                    capturedAtMs = System.currentTimeMillis()
                ),
                emptyMap()
            )
        }

        // Internal row so a diff and its after-image are kept or dropped
        // TOGETHER: leaving an after-image for a diff that was filtered out
        // would have handed the revert layer a path with no rendered diff -
        // and a diff for no reason. Identical content (the engine tracked a
        // file it touched without changing) is the shape that hits this.
        data class Row(val diff: FileDiff, val after: String?)

        val rows = ArrayList<Row>()
        val unreadable = ArrayList<String>()

        // Normalise FIRST, then dedupe. Deduping the raw strings left "a.kt" and
        // "./a.kt" as two entries, and the same file rendered twice - once as its
        // real edit and once as an identical duplicate, which reads to a user as
        // the agent having made the same change twice.
        //
        // A null from normalise is not "retry later" - it is "this path can
        // never name a diffable file under the workspace" (a foreign absolute
        // path). Dropping it via mapNotNull erased the agent's actual IO from
        // the report entirely, which is its own species of lie; unreadable is
        // the honest bucket, the same one collect() uses for every other path
        // it refuses to diff. Blank raws stay silent drops: the engine gave no
        // path, and rendering an empty entry would only confuse the UI.
        val resolved = ArrayList<Pair<String, String>>()
        val rejected = LinkedHashSet<String>()
        tracked.forEach { raw ->
            if (raw.isBlank()) return@forEach
            val rel = normalise(raw, workspace)
            if (rel == null) rejected.add(raw) else resolved.add(rel to raw)
        }
        unreadable.addAll(rejected)

        for ((rel, rawPath) in resolved.distinctBy { it.first }) {
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
                rows.add(Row(LineDiffer.diff(rel, beforeText, ""), null))
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
                    rows.add(Row(LineDiffer.diff(rel, "", after), after))
                }
                continue
            }

            val after = readOrNull(file, snapshotter)
            if (after == null) {
                unreadable.add(rawPath)
            } else {
                rows.add(Row(LineDiffer.diff(rel, beforeText, after), after))
            }
        }

        val kept = rows.filter { it.diff.hasChanges }
        return CollectedChanges(
            FileChangeSet(
                sessionId = null,
                diffs = kept.map { it.diff },
                unreadable = unreadable,
                snapshotUnavailable = false,
                capturedAtMs = before.capturedAtMs
            ),
            kept.associate { it.diff.path to it.after }
        )
    }

    /**
     * Reads the after-image, or null when it cannot be diffed honestly.
     *
     * TWO size guards, because file.length() and readText() are two separate
     * worlds. The pre-read check is the cheap rejection; the post-read check
     * covers the gap in between: a file the agent keeps appending to can pass
     * the length() check and then hand back a string several times the cap
     * (TOCTOU). The snapshotter applies the identical post-read rule and files
     * such a read under its truncated marker; here the honest equivalent is to
     * report the entry unreadable, because a "truncated" UX label cannot be
     * formed from a snapshot that already shipped - collect() cannot mutate
     * [WorkspaceSnapshotter.Snapshot], and fabricating a diff from a string we
     * know to be oversized is the lie this class exists to prevent.
     */
    private fun readOrNull(file: File, snapshotter: WorkspaceSnapshotter): String? =
        runCatching {
            if (file.length() > snapshotter.maxFileBytes) return null
            val text = file.readText()
            if (text.length > snapshotter.maxFileBytes) return null
            text
        }.getOrNull()

    /**
     * Maps an engine-tracked path onto a workspace-relative one, or null when
     * the path can never name a diffable file inside the workspace.
     *
     * ABSOLUTE PATHS used to slip through: this only stripped backslashes and a
     * "./" prefix, so a fully-qualified tracker entry built
     * `File(workspace, "/abs/path")` - which Java resolves by dropping the
     * root's parent directories, landing INSIDE the workspace on the device.
     * The after-image then read fine but the before-image lookup missed (the
     * snapshot is keyed by relative path), and the entry rendered as a creation
     * "+N -0" that never happened.
     *
     * Now an absolute path is reduced to its relative form when it sits under
     * one of the workspace's own spellings - both the raw absolute path and the
     * canonical one, because Android resolves /data/data through a symlink so
     * the engine's spelling and File's can disagree - and null is returned
     * otherwise, so collect() reports it in the unreadable list instead of
     * pretending it was created.
     */
    private fun normalise(raw: String, workspace: File): String? {
        if (raw.isBlank()) return null
        val rel = raw.replace('\\', '/').removePrefix("./").ifBlank { null } ?: return null
        // Device paths are POSIX-absolute ("/..."); the drive-letter form exists
        // only so the JVM tests run on a Windows dev box behave like the device.
        val isAbsolute = rel.startsWith("/") ||
            (rel.length >= 2 && rel[1] == ':')
        if (!isAbsolute) return rel

        // The roots list carries BOTH spellings and BOTH app-data aliases,
        // because canonicalFile does not always cross the /data/data <->
        // /data/user/0 alias on-device, and the engine records absolute paths
        // in whatever spelling the model chose to write in its tool call.
        fun aliases(p: String): List<String> {
            if (p.startsWith("/data/user/0/")) {
                return listOf(p, "/data/data/" + p.removePrefix("/data/user/0/"))
            }
            if (p.startsWith("/data/data/")) {
                return listOf(p, "/data/user/0/" + p.removePrefix("/data/data/"))
            }
            return listOf(p)
        }
        val roots = buildList {
            aliases(workspace.invariantSeparatorsPath).forEach(::add)
            runCatching { workspace.canonicalFile.invariantSeparatorsPath }
                .getOrNull()
                ?.let { aliases(it).forEach(::add) }
        }.distinct()
        for (root in roots) {
            // The workspace itself is not a diffable file: refusing keeps the
            // blank-relative case out of the "created" bucket too.
            if (rel == root) return null
            val prefix = "$root/"
            if (rel.startsWith(prefix)) {
                return rel.removePrefix(prefix).ifBlank { null }
            }
        }
        return null
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
