package com.crush.mobile.domain.file

import java.io.File

/**
 * Restores one changed file to its pre-turn state - or refuses, with a reason,
 * whenever the world no longer matches what the turn measured.
 *
 * THE ONLY GUARD THAT MATTERS
 *
 * A revert is trustworthy when it is a state machine over two measured images:
 * beforeText (the pre-turn snapshot) and afterText (what the collector read at
 * turn end). The file being touched TODAY must still equal [afterText], byte
 * for byte on the text plane. If it does not, someone edited the file after the
 * turn ended, and overwriting with a stale before-image would turn a feature
 * into a data-destroyer. Every path in the truth table below either acts on a
 * verified match or refuses with the concrete discrepancy named.
 *
 * THERE IS NO UNDO FOR THE REVERT ITSELF
 *
 * The pre-turn image is the last copy this layer holds. A second revert of the
 * same path is refused by the store, not silently repeated - a blind repeat
 * after a post-revert edit would be identical to ignoring the guarded world.
 */
class FileReverter {

    /** What a revert did, or why it didn't. */
    sealed interface Outcome {
        /** Which mechanical action produced the restore. */
        enum class Action {
            /** The turn modified the file; the pre-turn text was written back. */
            RESTORED_MODIFIED,
            /** The turn created the file; the file was deleted. */
            DELETED_CREATED,
            /** The turn deleted the file; the pre-turn text was written back, parents included. */
            RESTORED_DELETED
        }

        data class Done(val action: Action) : Outcome

        /**
         * Nothing was touched. [reason] is the user-facing sentence, so the
         * screen can show an honest refusal without inventing one.
         */
        data class Refused(val reason: String) : Outcome
    }

    /**
     * @param root       the run-frozen capture root this card measured.
     * @param path       the collector-normalised relative path of one diff.
     * @param beforeText pre-turn image, or null = the file did not exist before
     *   the turn (creation).
     * @param afterText  collect-time image, or null = the turn deleted the file.
     */
    fun revert(
        root: File,
        path: String,
        beforeText: String?,
        afterText: String?
    ): Outcome {
        if (!isInside(root, File(root, path))) {
            return Outcome.Refused("path escapes the workspace; refusing")
        }
        return when {
            beforeText != null && afterText != null -> revertModified(root, path, beforeText, afterText)
            beforeText == null && afterText != null -> revertCreated(root, path, afterText)
            beforeText != null && afterText == null -> revertDeleted(root, path, beforeText)
            else -> Outcome.Refused("no images were measured for this path; refusing")
        }
    }

    /**
     * Modified: the file must exist NOW and still read as the turn left it.
     * A missing file also counts as "changed after the turn" - deletion is a
     * change like any other, and writing a before-image into the void is not
     * what the user asked for.
     */
    private fun revertModified(
        root: File,
        path: String,
        beforeText: String,
        afterText: String
    ): Outcome {
        val current = readCurrentOrNull(root, path)
            ?: return Outcome.Refused("file is missing now; it changed after the turn")
        if (current != afterText) {
            return Outcome.Refused(
                "file changed after the turn; refusing to overwrite newer edits"
            )
        }
        return runCatching {
            File(root, path).writeText(beforeText)
        }.fold(
            onSuccess = { Outcome.Done(Outcome.Action.RESTORED_MODIFIED) },
            onFailure = { Outcome.Refused("could not write the restored file: ${it.message ?: "I/O error"}") }
        )
    }

    /**
     * Created: deleting is only faithful while the file is exactly what the
     * turn produced. A file deleted after the turn is ALREADY back to its
     * pre-turn state, but this revert did not do that - refusing keeps the
     * report true ("we did it" vs "it was already so" is the whole point of
     * the notes the store keeps).
     */
    private fun revertCreated(root: File, path: String, afterText: String): Outcome {
        val current = readCurrentOrNull(root, path)
            ?: return Outcome.Refused("file was already deleted after the turn")
        if (current != afterText) {
            return Outcome.Refused(
                "file changed after the turn; refusing to delete newer edits"
            )
        }
        return runCatching {
            File(root, path).delete()
        }.fold(
            onSuccess = { deleted ->
                if (deleted) Outcome.Done(Outcome.Action.DELETED_CREATED)
                else Outcome.Refused("could not delete the created file")
            },
            onFailure = { Outcome.Refused("could not delete the created file: ${it.message ?: "I/O error"}") }
        )
    }

    /**
     * Deleted: the path must not exist NOW. Anything sitting there was
     * recreated after the turn, and deleting it would be destruction with a
     * stale instruction. Parents are re-created because a turn may have
     * deleted a file together with (or inside) a now-empty directory tree.
     */
    private fun revertDeleted(root: File, path: String, beforeText: String): Outcome {
        val target = File(root, path)
        if (target.exists()) {
            return Outcome.Refused("this path exists again; it was created after the turn")
        }
        return runCatching {
            target.parentFile?.mkdirs()
            target.writeText(beforeText)
        }.fold(
            onSuccess = { Outcome.Done(Outcome.Action.RESTORED_DELETED) },
            onFailure = { Outcome.Refused("could not restore the deleted file: ${it.message ?: "I/O error"}") }
        )
    }

    /**
     * The file's text today, or null when it is absent/unreadable. Same
     * decoding plane as the collector (`readText`), so a same-text comparison
     * here is exact within the universe a diff can be formed from.
     */
    private fun readCurrentOrNull(root: File, path: String): String? {
        val f = File(root, path)
        if (!f.isFile) return null
        return runCatching { f.readText() }.getOrNull()
    }

    /**
     * Canonical-path containment, matching [FileChangeCollector]'s rule for
     * the paths that reach this method: string comparison would be defeated
     * by `../`, and by a symlink leaving the root. Path arguments arise from
     * collector-normalised diff paths, so this second check is defense in
     * depth over a store call, and it keeps an independently constructed
     * caller from ever escaping the root.
     */
    private fun isInside(root: File, candidate: File): Boolean = runCatching {
        val rootPath = root.canonicalPath
        val candPath = candidate.canonicalPath
        candPath == rootPath || candPath.startsWith(rootPath + File.separator)
    }.getOrDefault(false)
}
