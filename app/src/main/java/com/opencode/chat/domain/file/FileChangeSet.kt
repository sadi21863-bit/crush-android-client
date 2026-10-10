package com.opencode.chat.domain.file

/**
 * What a session changed, ready to render.
 *
 * The engine tracks which files a session touched but exposes no diff endpoint -
 * `GET /workspaces/{id}/sessions/{sid}/filetracker/files` returns
 * `array[string]` and nothing else. The workspace is the app's own
 * `filesDir/workspace`, so the client can read those files itself.
 *
 * The "before" side has to come from somewhere, and this is the honest part of
 * the design: [WorkspaceSnapshotter] captures the workspace when a prompt is
 * sent, and [FileChangeCollector] diffs the snapshot against the live files once
 * the turn ends. Without the pre-turn snapshot there is no before-image, and the
 * only options are showing nothing or inventing one. Neither is acceptable, and
 * showing a one-sided "+24 -0" for every file is exactly as misleading as the
 * delete bug was.
 */
data class FileChangeSet(
    val sessionId: String?,
    val diffs: List<FileDiff>,
    /** Paths the engine named that we could not read or find. */
    val unreadable: List<String>,
    /** True when the workspace snapshot was unavailable, so every diff is one-sided. */
    val snapshotUnavailable: Boolean,
    val capturedAtMs: Long
) {
    val totalAdded: Int get() = diffs.sumOf { it.added }
    val totalRemoved: Int get() = diffs.sumOf { it.removed }
    val fileCount: Int get() = diffs.count { it.hasChanges }
    val isEmpty: Boolean get() = diffs.none { it.hasChanges } && unreadable.isEmpty()

    fun stat(): String = "+$totalAdded -$totalRemoved across $fileCount file(s)"
}
