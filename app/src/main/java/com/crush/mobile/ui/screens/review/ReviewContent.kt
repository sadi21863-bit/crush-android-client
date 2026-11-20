package com.crush.mobile.ui.screens.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.crush.mobile.domain.file.TurnChanges
import com.crush.mobile.ui.components.CrushBanner
import com.crush.mobile.ui.components.CrushCard
import com.crush.mobile.ui.components.CrushEmptyState
import com.crush.mobile.ui.components.CrushTone
import com.crush.mobile.ui.screens.chat.FileDiffRow
import com.crush.mobile.ui.theme.CrushSpacing
import com.crush.mobile.ui.theme.CrushType
import com.crush.mobile.ui.theme.LocalCrushColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Card timestamps are read-only labels; a shared whole-day minute precision. */
private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.US)

/**
 * Phase 2b.3 (V2 plan §§5.6, 6.5): the Review surface, rendered only from
 * [ReviewUiState] plus two callbacks.
 *
 * This composable may not read a Context, the container, a ViewModel, an
 * Activity, or a SAF launcher. Revert dispatch and export are host-owned;
 * the pending-revert dialog is local `remember` state, as the sheets were in
 * 2b.1.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewContent(
    state: ReviewUiState,
    onBack: () -> Unit,
    onRevert: (cardId: Long, path: String) -> Unit,
    onExport: (rootPath: String, path: String) -> Unit,
) {
    // One pending revert at a time: the confirm dialog addresses one file of
    // one card, and Confirm/Cancel is the whole interaction.
    var pending by remember { mutableStateOf<Pair<TurnChanges, String>?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Review changes", style = CrushType.section)
                        state.projectLabel?.let { label ->
                            Text(
                                label,
                                style = CrushType.metadata,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Text(
                "Per-turn changes for ${state.projectLabel ?: "this chat"}. " +
                    "History clears when the app process ends. Revert targets " +
                    "the latest completed turn only. Tests are not run by Crush " +
                    "Mobile; model claims are not test results. Export copies " +
                    "the file as it is on disk now.",
                style = CrushType.secondary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    horizontal = CrushSpacing.lg,
                    vertical = CrushSpacing.xs
                )
            )

            if (state.runInFlight) {
                CrushBanner(
                    text = "A turn is running; revert is disabled until the agent is idle.",
                    tone = CrushTone.Warning,
                    modifier = Modifier.padding(
                        horizontal = CrushSpacing.lg,
                        vertical = CrushSpacing.xs
                    )
                )
            }

            state.exportMessage?.let {
                Text(
                    it,
                    style = CrushType.secondary,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(
                        horizontal = CrushSpacing.lg,
                        vertical = CrushSpacing.xs
                    )
                )
            }

            when {
                !state.scopeAvailable -> CrushEmptyState(
                    title = "No file changes in this chat",
                    body = "Chats run no tools, so there is no project diff to " +
                        "review. Open a project chat to collect file changes.",
                    modifier = Modifier.padding(CrushSpacing.xl)
                )

                state.cards.isEmpty() -> CrushEmptyState(
                    title = "Nothing collected yet",
                    body = "Send a prompt in a project chat; when a turn ends, " +
                        "the files it changed appear here with their diffs.",
                    modifier = Modifier.padding(CrushSpacing.xl)
                )

                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(
                        start = CrushSpacing.md,
                        end = CrushSpacing.md,
                        bottom = CrushSpacing.xl
                    ),
                    verticalArrangement = Arrangement.spacedBy(CrushSpacing.sm)
                ) {
                    items(state.cards, key = { it.id }) { card ->
                        TurnCard(
                            card = card,
                            runInFlight = state.runInFlight,
                            onRevert = { path -> pending = card to path },
                            onExport = { path -> onExport(card.rootPath, path) }
                        )
                    }
                }
            }
        }
    }

    pending?.let { (card, path) ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Revert this file?") },
            text = {
                Text(
                    "Rewrites \"$path\" to the content it had before the " +
                        "turn at ${timeLabel(card.startedAtMs)} in " +
                        "${state.projectLabel ?: "this chat"}. The revert " +
                        "refuses automatically if the file changed after that " +
                        "turn ended."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRevert(card.id, path)
                    pending = null
                }) {
                    Text("Revert", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun TurnCard(
    card: TurnChanges,
    runInFlight: Boolean,
    onRevert: (String) -> Unit,
    onExport: (String) -> Unit
) {
    CrushCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Turn at ${timeLabel(card.startedAtMs)}",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    cardLabelDetail(card),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                if (card.changes.snapshotUnavailable) {
                    "not measured"
                } else {
                    "${card.changes.fileCount} file(s) " +
                        "+${card.changes.totalAdded} -${card.changes.totalRemoved}"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (card.changes.snapshotUnavailable) {
            Text(
                "Changes were not measured for this turn. The pre-turn " +
                    "snapshot was unavailable, so nothing is offered for revert.",
                style = MaterialTheme.typography.labelSmall,
                color = LocalCrushColors.current.warning,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (card.superseded) {
            Text(
                "Older turn - read-only; only the latest completed turn is revertible.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (card.changes.unreadable.isNotEmpty()) {
            Text(
                "${card.changes.unreadable.size} tracked file(s) could not be " +
                    "diffed (binary, oversize, or foreign path): " +
                    card.changes.unreadable.take(3).joinToString(", ") +
                    if (card.changes.unreadable.size > 3) ", ..." else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (card.changes.diffs.isEmpty() && !card.changes.snapshotUnavailable) {
            Text(
                "No diffable file change in this turn.",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        card.changes.diffs.forEach { diff ->
            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
            FileDiffRow(diff)
            RevertRow(card, diff.path, runInFlight, onRevert)
            ExportRow(diff.path, onExport)
        }
    }
}

@Composable
private fun RevertRow(
    card: TurnChanges,
    path: String,
    runInFlight: Boolean,
    onRevert: (String) -> Unit
) {
    val reverted = path in card.reverted
    val revertible = !card.superseded && !runInFlight && !reverted
    val note = card.notes[path]

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(
            onClick = { onRevert(path) },
            enabled = revertible,
            contentPadding = PaddingValues(
                horizontal = 12.dp, vertical = 4.dp
            )
        ) {
            Text(
                when {
                    reverted -> "Reverted"
                    else -> "Revert this file"
                }
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            when {
                note != null -> (if (note.ok) "Done: " else "Not reverted: ") + note.message
                card.superseded -> "older turn: read-only"
                runInFlight -> "turn running"
                reverted -> "already reverted from this turn"
                else -> "restores what this turn measured"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (note?.ok == true) LocalCrushColors.current.success
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * Export a byte-exact copy of the file AS IT SITS ON DISK NOW (P4 proof:
 * "reviews each hunk and exports/preserves the result"). Always enabled, even
 * for a turn-deleted path - the host's launcher reply delivers the honest
 * "file is gone; nothing to export" sentence instead of pre-filtering rows.
 */
@Composable
private fun ExportRow(path: String, onExport: (String) -> Unit) {
    TextButton(
        onClick = { onExport(path) },
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
    ) {
        Text(
            "Export a copy",
            style = MaterialTheme.typography.labelSmall
        )
    }
}

/**
 * Orientation for multi-project histories: the workspace tail (which folder)
 * plus the short session id. Not parsed for logic - only for reading.
 */
private fun cardLabelDetail(card: TurnChanges): String {
    val root = card.rootPath.trimEnd('/', '\\')
    val tail = root.substringAfterLast('/').ifBlank { root }
    val sid = card.sessionId?.let { "session ${it.take(8)}" } ?: "session"
    val captured = card.changes.capturedAtMs
        .takeIf { it > 0L }
        ?.let { "captured ${timeLabel(it)}" }
    return listOfNotNull(sid, tail, captured).joinToString(" \u00b7 ")
}

private fun timeLabel(ms: Long): String = TIME_FORMAT.format(Date(ms))
