package com.crush.mobile.ui.screens.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.crush.mobile.CrushMobileApp
import com.crush.mobile.domain.file.RevertNote
import com.crush.mobile.domain.file.TurnChanges
import com.crush.mobile.ui.screens.chat.FileDiffRow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Card timestamps are read-only labels; a shared whole-day minute precision. */
private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.US)

/**
 * The Review surface (P4 v1, 2026-11-07): every completed turn the process
 * measured, each hunk readable, each file revertable to what it was before
 * that turn - with the refusals the review layer manufactures shown as
 * sentences, not error badges.
 *
 * DATA SOURCE IS CONTAINER-SCOPED ON PURPOSE: this screen holds no engine
 * connection and no ChatViewModel. The chat ViewModel writes turn cards; this
 * screen reads and reverts through [com.crush.mobile.domain.file.ReviewHistory],
 * whose gates refuse superseded cards and mid-run surgery. Honest caps are part
 * of the copy: history lives in process memory and clears when the app dies.
 */
@Composable
fun ReviewRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val history = remember {
        (context.applicationContext as CrushMobileApp).container.reviewHistory
    }
    val cards by history.cards.collectAsStateWithLifecycle()
    val runInFlight by history.runInFlight.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // One pending revert at a time: the confirm dialog addresses one file of
    // one card, and Confirm/Cancel is the whole interaction.
    var pending by remember { mutableStateOf<Pair<TurnChanges, String>?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 24.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Spacer(Modifier.width(8.dp))
            Text(
                "Review changes",
                style = MaterialTheme.typography.titleLarge
            )
        }
        Text(
            "Per-turn file changes recorded while the app runs. " +
                "History clears when the app process ends. Revert targets the " +
                "latest completed turn only, and refuses files edited after it.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        if (runInFlight) {
            Text(
                "A turn is running; revert is disabled until the agent is idle.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }

        if (cards.isEmpty()) {
            Spacer(Modifier.height(32.dp))
            Text(
                "Nothing collected yet.\n\n" +
                    "Send a prompt in a chat; when a turn ends, the files it " +
                    "changed appear here with their diffs.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(
                    start = 12.dp, end = 12.dp, bottom = 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(cards, key = { it.id }) { card ->
                    TurnCard(
                        card = card,
                        runInFlight = runInFlight,
                        onRevert = { path -> pending = card to path }
                    )
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
                        "turn at ${timeLabel(card.startedAtMs)}. " +
                        "The revert refuses automatically if the file changed " +
                        "after that turn ended."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { history.revert(card.id, path) }
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
    onRevert: (String) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
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
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    "${card.changes.fileCount} file(s) " +
                        "+${card.changes.totalAdded} -${card.changes.totalRemoved}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
            if (card.changes.diffs.isEmpty()) {
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
            }
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
            color = if (note?.ok == true) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
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
    return "$sid - $tail"
}

private fun timeLabel(ms: Long): String = TIME_FORMAT.format(Date(ms))
