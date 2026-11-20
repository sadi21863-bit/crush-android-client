package com.crush.mobile.domain.file

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 *
 * THE OPERATION GATE
 *
 * Those flags guard WHO may ask, not WHEN the file surgery happens. A turn
 * beginning while a revert's IO work is in flight could write over the
 * reverter's read-compare-write mid-restore. One private mutex ([opGate])
 * serializes the store's own record/revert seam: revert() holds it across
 * its whole per-file operation (checks, file work, card update), and the
 * fly-flag flips of [beginTurn]/[runSettled]/[clear] wait on the same gate
 * for their short in-memory flips. Nothing here blocks an engine process;
 * a run simply cannot RAISE its flag until no revert owns the moment.
 */
class ReviewHistory(private val reverter: FileReverter = FileReverter()) {

    companion object {
        /** Enough turns for a working scope; bounded anyway, like the snapshot. */
        private const val MAX_CARDS = 8

        /** Sentinel used by the legacy no-arg beginTurn(), which supersedes all. */
        private const val ALL_SCOPES = "*"
    }

    private val _cards =
        MutableStateFlow<List<TurnChanges>>(emptyList())

    /** Newest first across all scopes; the Review host filters by its root. */
    val cards: StateFlow<List<TurnChanges>> = _cards.asStateFlow()

    /**
     * V2-5 D1: the roots with a turn in flight. Revert is refused only for a
     * card in a running scope; project A's turn no longer disables project B.
     */
    private val _runningScopes = MutableStateFlow<Set<String>>(emptySet())
    val runningScopes: StateFlow<Set<String>> = _runningScopes.asStateFlow()

    /**
     * True while any scope is running. Kept for callers/tests that only need
     * the global fact; Review uses [runningScopes] for per-project gating.
     */
    private val _runInFlight = MutableStateFlow(false)
    val runInFlight: StateFlow<Boolean> = _runInFlight.asStateFlow()

    /**
     * The record/revert seam's lock (class doc, THE OPERATION GATE): [revert]
     * holds it across its whole operation; the fly-flag flips below wait on
     * the same gate so no turn can begin its record over a revert's file work.
     */
    private val opGate = Mutex()

    private val nextId = AtomicLong(1)

    /**
     * A new turn was sent in [rootPath]: that scope's fly flag rises for the
     * whole send/stream/collect window, and only that scope's existing cards
     * go read-only. Called by the ViewModel with the run-frozen capture root.
     *
     * The flip waits on [opGate], so a turn cannot begin its record while a
     * revert's file operation is still in flight.
     */
    suspend fun beginTurn(rootPath: String) {
        beginTurnScoped(rootPath)
    }

    /**
     * Compatibility entry for callers/tests that model ONE global scope
     * (pre-V2-5 behavior): every card is superseded and every revert is
     * blocked until [runSettled] without an argument. Production uses the
     * root-scoped overload.
     */
    suspend fun beginTurn() {
        beginTurnScoped(null)
    }

    private suspend fun beginTurnScoped(rootPath: String?) {
        opGate.withLock {
            _runningScopes.update { scopes ->
                if (rootPath == null) scopes + ALL_SCOPES else scopes + rootPath
            }
            _runInFlight.value = true
            _cards.update { list ->
                list.map { card ->
                    if ((rootPath == null || card.rootPath == rootPath) && !card.superseded) {
                        card.copy(superseded = true)
                    } else {
                        card
                    }
                }
            }
        }
    }

    /**
     * The scoped turn is over in every sense (the ViewModel's streamSend
     * finally calls this AFTER collect, so a completed turn is revertible the
     * moment its own card exists).
     *
     * The flip is NonCancellable: a finally can run on an already-cancelled
     * coroutine, and a stuck-up flag would strand the store's gates forever.
     * It still waits on [opGate] for its brief flip.
     */
    suspend fun runSettled(rootPath: String) {
        withContext(NonCancellable) {
            opGate.withLock {
                _runningScopes.update { it - rootPath }
                _runInFlight.value = _runningScopes.value.isNotEmpty()
            }
        }
    }

    /** Compatibility entry: clears every scope (pre-V2-5 single-scope tests). */
    suspend fun runSettled() {
        withContext(NonCancellable) {
            opGate.withLock {
                _runningScopes.value = emptySet()
                _runInFlight.value = false
            }
        }
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
        // An ordinary empty outcome is not a card. A turn whose snapshot was
        // unavailable is DIFFERENT: "we did not measure" must be reviewable,
        // or the surface silently equates it with "nothing changed" (V2-5 D2).
        if (changes.isEmpty && !changes.snapshotUnavailable) return null
        val card = TurnChanges(
            id = nextId.getAndIncrement(),
            startedAtMs = startedAtMs,
            sessionId = sessionId,
            rootPath = rootPath,
            changes = changes,
            beforeTexts = beforeTexts,
            afterTexts = afterTexts
        )
        _cards.update { list ->
            val seen = mutableMapOf<String, Int>()
            (listOf(card) + list).filter { candidate ->
                val count = seen.getOrDefault(candidate.rootPath, 0)
                if (count < MAX_CARDS) {
                    seen[candidate.rootPath] = count + 1
                    true
                } else {
                    false
                }
            }
        }
        return card.id
    }

    /** Clears the history (process death does this naturally; tests do it).
     *  The flip takes the same gate, so no in-flight revert is interrupted. */
    suspend fun clear() {
        opGate.withLock {
            _cards.value = emptyList()
            _runningScopes.value = emptySet()
            _runInFlight.value = false
        }
    }

    /**
     * Reverts ONE path of ONE card to that turn's before-image, under both
     * refusal gates and the reverter's own changed-after-the-turn guard.
     *
     * The WHOLE operation - the checks, the file work and the card update -
     * holds [opGate] (class doc, THE OPERATION GATE): a turn's flag raise
     * cannot happen until this body has left the gate, so no engine write of
     * the next turn can race the reverter's read-compare-write.
     *
     * The result is written back into the card as a note, so the UI shows the
     * concrete outcome (including a refusal's reason) even after recomposition,
     * instead of a transient snackbar the user's scroll loses.
     */
    suspend fun revert(cardId: Long, path: String): FileReverter.Outcome = opGate.withLock {
        val refusal = { reason: String ->
            note(cardId, path, FileReverter.Outcome.Refused(reason))
        }
        val card = _cards.value.firstOrNull { it.id == cardId }
            ?: return@withLock refusal("this turn is no longer in the history")
        val running = _runningScopes.value
        if (ALL_SCOPES in running || card.rootPath in running) {
            return@withLock refusal("a turn is running; revert while the agent is idle")
        }
        if (card.superseded) {
            return@withLock refusal("older turn; only the latest completed turn is revertible")
        }
        if (path !in card.beforeTexts.keys) {
            return@withLock refusal("this path is not part of that turn's changes")
        }
        if (path in card.reverted) {
            return@withLock refusal("already reverted")
        }
        val outcome = withContext(Dispatchers.IO) {
            reverter.revert(
                root = File(card.rootPath),
                path = path,
                beforeText = card.beforeTexts[path],
                afterText = card.afterTexts[path]
            )
        }
        when (outcome) {
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
