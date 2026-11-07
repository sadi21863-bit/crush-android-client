package com.crush.mobile.domain.file

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * The Review surface's runtime state (P4 v1): every completed turn's measured
 * changes, kept in PROCESS MEMORY only, with per-file revert on the latest
 * turn.
 *
 * WHY THIS LIVES AT THE CONTAINER, NOT IN CHATVIEWMODEL
 *
 * A ChatViewModel instance is scoped to its destination's back-stack entry:
 * each project row open replaces that stack (E5) and DESTROYS the entry with
 * its ViewModel. The Review screen must survive exactly that navigation every
 * day - it is reached from the chat sheet and the rail, and back must not
 * forget what the user came to revert. The store is therefore process-scoped,
 * written by the ViewModel at the two moments it owns (send, collect) and read
 * by the Review screen, which holds no engine connection of its own.
 *
 * WHY STILL NOT PERSISTED
 *
 * The before-images are already memory-capped (256KB/file, 4MB total); a
 * journal that survives restart would ALSO have to pin the engine's duty to
 * not re-use those files alone, which is P4 v2 work (the recorded follow-up).
 * Honesty about this is cheap: the Review screen says history clears when the
 * process ends.
 *
 * THE TWO REFUSAL GATES
 *
 * Revert is only ever offered for the LATEST completed turn, and only while
 * no turn is in flight (the sheet marks it at send; the fly flag clears in the
 * turn's finally). Both gates are enforced HERE so no caller can bypass them,
 * and the wording describes what actually happened rather than a generic
 * failure.
 */
class ReviewHistory(private val reverter: FileReverter = FileReverter()) {

    companion object {
        /** Enough turns for a working session; bounded anyway, like the snapshot. */
        private const val MAX_CARDS = 8
    }

    private val _cards =
        MutableStateFlow<List<TurnChanges>>(emptyList())

    /** Newest first. */
    val cards: StateFlow<List<TurnChanges>> = _cards.asStateFlow()

    private val _runInFlight = MutableStateFlow(false)
    val runInFlight: StateFlow<Boolean> = _runInFlight.asStateFlow()

    private val nextId = AtomicLong(1)

    /**
     * A new turn was sent: the fly flag rises for the whole send/stream/collect
     * window, and every existing card goes read-only. Called by the ViewModel
     * where the capture root freezes; see [ChatViewModel.streamSend].
     */
    fun beginTurn() {
        _runInFlight.value = true
        _cards.update { list -> list.map { if (it.superseded) it else it.copy(superseded = true) } }
    }

    /**
     * The turn is over in every sense (the ViewModel's streamSend finally calls
     * this AFTER collect, so a completed turn is revertible the moment its
     * own card exists).
     */
    fun runSettled() {
        _runInFlight.value = false
    }

    /**
     * Records one completed turn. Empty outcomes (no diffable file, nothing
     * unreadable) are NOT recorded: a card with neither diffs nor unreadable
     * entries has nothing to review or restore, and a wall of "nothing
     * changed" cards would bury the turn that did change something.
     *
     * @return the card id, or null when nothing was recorded.
     */
    fun recordTurn(
        sessionId: String?,
        startedAtMs: Long,
        rootPath: String,
        changes: FileChangeSet,
        beforeTexts: Map<String, String?>,
        afterTexts: Map<String, String?>
    ): Long? {
        if (changes.isEmpty) return null
        val card = TurnChanges(
            id = nextId.getAndIncrement(),
            startedAtMs = startedAtMs,
            sessionId = sessionId,
            rootPath = rootPath,
            changes = changes,
            beforeTexts = beforeTexts,
            afterTexts = afterTexts
        )
        _cards.update { list -> (listOf(card) + list).take(MAX_CARDS) }
        return card.id
    }

    /** Clears the history (process death does this naturally; tests do it). */
    fun clear() {
        _cards.value = emptyList()
        _runInFlight.value = false
    }

    /**
     * Reverts ONE path of ONE card to that turn's before-image, under both
     * refusal gates and the reverter's own changed-after-the-turn guard.
     *
     * The result is written back into the card as a note, so the UI shows the
     * concrete outcome (including a refusal's reason) even after recomposition,
     * instead of a transient snackbar the user's scroll loses.
     */
    suspend fun revert(cardId: Long, path: String): FileReverter.Outcome {
        val refusal = { reason: String ->
            note(cardId, path, FileReverter.Outcome.Refused(reason))
        }
        if (_runInFlight.value) {
            return refusal("a turn is running; revert while the agent is idle")
        }
        val card = _cards.value.firstOrNull { it.id == cardId }
            ?: return refusal("this turn is no longer in the history")
        if (card.superseded) {
            return refusal("older turn; only the latest completed turn is revertible")
        }
        if (path !in card.beforeTexts.keys) {
            return refusal("this path is not part of that turn's changes")
        }
        if (path in card.reverted) {
            return refusal("already reverted")
        }
        val outcome = withContext(Dispatchers.IO) {
            reverter.revert(
                root = File(card.rootPath),
                path = path,
                beforeText = card.beforeTexts[path],
                afterText = card.afterTexts[path]
            )
        }
        return when (outcome) {
            is FileReverter.Outcome.Done -> {
                _cards.update { list ->
                    list.map {
                        if (it.id != cardId) it
                        else it.copy(
                            reverted = it.reverted + path,
                            notes = it.notes + (path to RevertNote(true, doneMessage(outcome)))
                        )
                    }
                }
                outcome
            }
            is FileReverter.Outcome.Refused -> refusal(outcome.reason)
        }
    }

    private fun doneMessage(done: FileReverter.Outcome.Done): String = when (done.action) {
        FileReverter.Outcome.Action.RESTORED_MODIFIED -> "restored to its pre-turn content"
        FileReverter.Outcome.Action.DELETED_CREATED -> "created file deleted"
        FileReverter.Outcome.Action.RESTORED_DELETED -> "deleted file restored"
    }

    /**
     * Writes an honest refusal as the card's note and returns it, so the
     * caller and the screen agree on the same sentence without a second
     * channel.
     */
    private fun note(
        cardId: Long,
        path: String,
        outcome: FileReverter.Outcome.Refused
    ): FileReverter.Outcome {
        _cards.update { list ->
            list.map {
                if (it.id != cardId) it
                else it.copy(notes = it.notes + (path to RevertNote(false, outcome.reason)))
            }
        }
        return outcome
    }
}
