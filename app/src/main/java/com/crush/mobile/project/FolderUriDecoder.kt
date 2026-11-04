package com.crush.mobile.project

/**
 * Maps a SAF folder-tree document id to the plain filesystem path the ENGINE
 * (a native Go child of our process) must work on.
 *
 * "Import folder as project" (2026-11-04, user request): a folder the user
 * made in the phone's Files app, e.g. `/storage/emulated/0/Test project
 * folder`, becomes a project whose workspace IS that real folder - the agent
 * edits files in place, visible in any file manager. The picker yields a
 * content:// tree URI the engine cannot use directly, but for the two
 * volumes almost every phone has (primary emulated storage) the tree id
 * (`primary:<rest>`) IS the plain path under `/storage/emulated/0`. This
 * decoder performs exactly that one mapping and refuses everything else:
 *
 * - refusing is honest: a non-primary tree (USB drive, Downloads provider,
 *   saved-googledrive entry) has NO real path we can hand to the engine;
 * - `..` segments are refused outright (defense in depth: folder names on
 *   the volume cannot contain them, and a forged id must not escape);
 * - the decoder is deliberately PURE (a string in, a string out) so the
 *   contract holds in the JVM unit suite without Android framework shims.
 */
object FolderUriDecoder {

    /** Android's fixed root for primary emulated storage. */
    const val PRIMARY_ROOT: String = "/storage/emulated/0"

    /** The authority DocumentsUI uses for the device's own volumes. */
    const val PRIMARY_TREE_PREFIX: String = "primary:"

    /**
     * @param treeDocumentId the value of
     *   `DocumentsContract.getTreeDocumentId(uri)` - e.g. "primary:test
     *   project folder". Null-safe; returns null for every tree the app
     *   cannot honestly represent as an engine workspace.
     */
    fun primaryPathFromTreeDocumentId(treeDocumentId: String?): String? {
        if (treeDocumentId == null) return null
        if (!treeDocumentId.startsWith(PRIMARY_TREE_PREFIX)) return null
        val rel = treeDocumentId.removePrefix(PRIMARY_TREE_PREFIX)
        if (rel.contains("..")) return null
        return when {
            rel.isEmpty() -> PRIMARY_ROOT
            else -> "$PRIMARY_ROOT/$rel"
        }
    }

    /** The user-facing label for a path this decoder produced. */
    fun displayNameFor(path: String): String =
        path.substringAfterLast('/').ifEmpty { path }
}
