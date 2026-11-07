package com.crush.mobile.domain.file

/**
 * One completed turn's measured file changes, retained in MEMORY for the
 * Review surface (P4 v1, 2026-11-07).
 *
 * WHY A RETAINED COPY, NOT A RE-DIFF
 *
 * The rail shows only the latest turn ([ChatViewModel.ChatUiState.fileChanges]
 * is replaced each turn), and the pre-turn snapshot is a single slot that the
 * next `captureBefore` overwrites. Reverting an EARLIER turn would therefore
 * have to re-derive its before-image from a snapshot that no longer exists,
 * which is a fabrication decision made under pressure. The Review card instead
 * copies every image it needs at collect time, so what it offers to restore is
 * exactly what it measured - and an older turn's card degrades honestly to
 * read-only once a later turn begins, because layered reverts are exactly how
 * this surface would have ended up lying.
 *
 * WHY TEXT, NOT BYTES
 *
 * The whole diff pipeline ([WorkspaceSnapshotter], [FileChangeCollector],
 * [LineDiffer]) reads and writes `String` via UTF-8 `readText`/`writeText`.
 * Storing bytes here would fork the notion of "what the turn wrote" between
 * the card and the diff, and a revert would no longer restore what the user
 * reviewed. A file we cannot decode as UTF-8 never enters [changes] anyway
 * (it lands in `unreadable`), so only paths on this text plane are revertible.
 */
data class TurnChanges(
    /** Monotonic per-process id, for addressing one card in calls. */
    val id: Long,
    /** When the turn's prompt was SENT (the card's label and its window). */
    val startedAtMs: Long,
    val sessionId: String?,
    /**
     * The run-frozen capture root, verbatim. Revert must target THIS
     * directory even if another project has since become active, or a
     * card from project A would rewrite project B by relative-path accident.
     */
    val rootPath: String,
    /** The rendered, reviewable diff. */
    val changes: FileChangeSet,
    /**
     * Pre-turn image per diff path; a null value means the file did not
     * exist before the turn (the diff is a creation). Every diff path has
     * an entry - a path missing here is a construction bug, refuse loudly.
     */
    val beforeTexts: Map<String, String?>,
    /**
     * Collect-time image per diff path; a null value means the turn deleted
     * the file. Same completeness guarantee as [beforeTexts].
     */
    val afterTexts: Map<String, String?>,
    /**
     * Paths already reverted from this card. Recorded so the UI can show
     * what happened instead of guessing, not because a second revert of the
     * same path is meaningful - the store refuses it.
     */
    val reverted: Set<String> = emptySet(),
    /**
     * True once a LATER turn began, literally: the next [FileChangeCollector]
     * capture replaces the substrate this card guarantees. A superseded card
     * still renders its diff (it is the only honest record of that turn) but
     * every revert on it is refused.
     */
    val superseded: Boolean = false,
    /**
     * Latest revert outcome per path, kept so the UI can report an honest
     * refusal even after a recomposition. Not a ledger: only the LAST note
     * for a path is kept.
     */
    val notes: Map<String, RevertNote> = emptyMap()
)

/** The user-facing consequence of one revert attempt on one path. */
data class RevertNote(
    val ok: Boolean,
    /**
     * Plain wording for the screen. Refusals carry the concrete reason
     * ("file changed after the turn") - "failed" alone would invite blind
     * retries.
     */
    val message: String
)
