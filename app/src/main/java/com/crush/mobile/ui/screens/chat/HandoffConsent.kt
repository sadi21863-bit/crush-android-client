package com.crush.mobile.ui.screens.chat

import com.crush.mobile.data.handoff.HandoffEntity
import com.crush.mobile.domain.handoff.HandoffPackageDraft
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The pure contract layer for the P6 Batch C Chat->Code handoff consent
 * sheet (build plan `build-plan-handoff-p6-2026-11-08` §Batch C/D).
 *
 * Everything here is pure so the sheet's composable owns NO consequential
 * state and the ViewModel's write order is pinned by test without an engine
 * or a Compose UI harness:
 *
 * - [handoffSheetFor] builds the sheet's state from the transcript with
 *   Batch B's `prefill` as the ONLY summary wording source;
 * - [handoffConfirmEnabled] is the exact Confirm gate;
 * - [handoffDispatchNote] is the provenance note line in the transcript;
 * - [handoffSessionTitle] / [handoffSourceStamp] are the dispatch title and
 *   provenance-time shapes.
 *
 * The row states themselves (DRAFT -> APPROVED -> DISPATCHED, guarded,
 * "cancel never inserted") ride the data layer untouched ([HandoffDao] -
 * its guarded transitions are the enforcement); this file only names what
 * the composer level passes into it, and how Cancel and Confirm map to
 * those states (see the ViewModel region's comment block for the mapping).
 */

/**
 * The consent sheet's compose-time state. The draft is the Batch B prefill
 * product (its [HandoffPackageDraft.summaryMarkdown] is what the composer
 * initialises from and carries the source provenance the first turn
 * renders); [HandoffPackageDraft.sourceChatId] stays null until the
 * composer's best-known id is supplied - it is the ONLY field that can
 * refuse a seal (Batch B's contract), so the sheet carries it inside the
 * draft rather than inventing a second channel.
 *
 * [existingDraft] is an untouched Room DRAFT row re-opened through the
 * current-draft lookup (Batch A's `latestDraftForSource` - the DAO's own
 * Batch C contract, "instead of minting a twin"): when present and still
 * UNTOUCHED, its stored summary is what the composer re-opens with, never
 * the machine prefill.
 */
data class HandoffSheetState(
    val draft: HandoffPackageDraft,
    /** A still-untouched Room DRAFT row for this source chat, or null (DRAFT mode). */
    val existingDraft: HandoffEntity? = null,
    /**
     * A resumable APPROVED row for this source chat (Batch D), or null
     * (RESUME mode): a dispatch that failed before the guarded stamp leaves
     * the row APPROVED and resumable, and the sheet's re-open surfaces it.
     * The approval never transfers in place: Confirm on the SAME destination
     * dispatches on the row's existing approval (the DAO's single-shot
     * markDispatched from APPROVED), a changed destination abandons the row
     * and re-approves a fresh draft (the no-alias rule, plan :416).
     */
    val approvedRow: HandoffEntity? = null,
    /**
     * The durable DISPATCHED trace for this source chat (Batch D), or null
     * (jump mode): its target Code session is re-openable through the drawer
     * machinery. The trace row itself can never be discarded or re-approved
     * from here - the DAO guard refuses (the dispatch surface offers only
     * Cancel and "Open in Code").
     */
    val dispatchedRow: HandoffEntity? = null,
) {
    /**
     * The summary the sheet's composer initialises. The three slots are
     * mutually exclusive (each `with*` builder clears the others), and the
     * priority order is the mode priority: the resumable approval shows the
     * approved summary, the durable trace its dispatched summary, the draft
     * its own text, and the fresh open the machine prefill.
     */
    val initialSummary: String
        get() = approvedRow?.summaryMarkdown
            ?: dispatchedRow?.summaryMarkdown
            ?: existingDraft?.summaryMarkdown
            ?: draft.summaryMarkdown

    /** Display provenance: the source session's title as the app knows it. */
    val sourceChatTitle: String get() = draft.sourceChatTitle

    /**
     * The composer-borne source chat id; null refuses the seal at Confirm
     * (honest notice, no row), never a fabricated engine reference.
     */
    val sourceChatId: String? get() = draft.sourceChatId

    /**
     * Attaches the DRAFT slot (Batch A's `latestDraftForSource`), the
     * re-open its own contract describes: the user's untouched draft is what
     * the composer opens with, never a minted twin. Clears the other two
     * slots - the three modes are mutually exclusive by construction.
     */
    fun withExisting(entity: HandoffEntity): HandoffSheetState =
        copy(existingDraft = entity, approvedRow = null, dispatchedRow = null)

    /**
     * Attaches the RESUME slot: the sheet re-opens as the approved row's
     * resume surface (Batch D) - the approved summary, the approved
     * destination preselected, the guarded dispatch re-stamp as Confirm.
     */
    fun withApproved(entity: HandoffEntity): HandoffSheetState =
        copy(approvedRow = entity, existingDraft = null, dispatchedRow = null)

    /**
     * Attaches the DISPATCHED slot: the sheet re-opens as the trace's jump
     * surface (Batch D) - read-only record, "Open in Code" as the move.
     */
    fun withDispatched(entity: HandoffEntity): HandoffSheetState =
        copy(dispatchedRow = entity, existingDraft = null, approvedRow = null)
}

/**
 * Builds the sheet state for a tap on the "Use this plan in Code" chip
 * under the transcript row at [tappedIndex].
 *
 * The message list passed in is PREFIXED at the tapped row: Batch B's
 * `prefill(messages, sourceSessionTitle)` contract stays the only summary
 * wording source, and processing `messages.take(tappedIndex + 1)` makes the
 * tapped bubble the prefix's last completed assistant row - the text the
 * Copy chip under that very bubble hands the user. The chip's visibility
 * gate ([handoffEntryVisible]) is the same conjunction prefill's candidate
 * must pass, so a visible tap always prefills the tapped text.
 *
 * [sourceChatId] may be null (no bound session yet): the sheet still opens
 * fully, with the row's later seal refusing it - the degrade shape (the
 * draft is app-side state, not engine state).
 */
internal fun handoffSheetFor(
    messages: List<ChatMessage>,
    tappedIndex: Int,
    sourceChatId: String?,
    sourceSessionTitle: String,
    sourceUpdatedAtSec: Long = 0L,
): HandoffSheetState {
    val draft = HandoffPackageDraft.prefill(
        messages.take(tappedIndex + 1),
        sourceSessionTitle,
    ).copy(
        sourceChatId = sourceChatId,
        sourceChatTime = handoffSourceStamp(sourceUpdatedAtSec),
    )
    return HandoffSheetState(draft = draft)
}

/**
 * The completed-assistant gate the actions row renders under - the
 * Copy-gate's conjunction EXACTLY (ChatScreen's bubble guard, extracted
 * verbatim as one expression): a completed (not streaming), non-error
 * assistant row with non-empty text. The handoff chip lives INSIDE the
 * same row, so it carries this gate by construction; extracting it as a
 * named predicate is what makes "same guard as Copy/Save" provable rather
 * than incidental.
 */
internal fun handoffEntryVisible(m: ChatMessage): Boolean =
    m.role != "user" && !m.isStreaming && m.error == null && m.text.isNotEmpty()

/**
 * The sheet's Confirm gate: a destination must be chosen; the summary may
 * stay empty (degrade law - empty hands off an empty approved summary, and
 * the first turn renders it as an empty APPROVED SUMMARY block).
 */
internal fun handoffConfirmEnabled(selectedDestinationId: String?): Boolean =
    selectedDestinationId != null

/**
 * Whether [entity] may still be REWRITTEN in place by a confirm (the DAO's
 * @Upsert covenant: a draft is re-writable by its own id only while
 * UNTOUCHED - still DRAFT, no approval stamp, no target session). Anything
 * that has moved on is a trace the composer can never clobber; a target
 * change then expresses as a NEW draft row (the no-alias rule, plan :416).
 */
internal fun handoffUsableExistingDraft(entity: HandoffEntity?): Boolean =
    entity != null && entity.userApprovedAt == null &&
        entity.status == HandoffEntity.STATUS_DRAFT

/**
 * The sheet's three modes (Batch D), decided once and read everywhere else
 * as a value: the slots are mutually exclusive by construction (the `with*`
 * builders clear each other), so the mode priority is the classification the
 * ViewModel's trace scan already performed - a resumable approval outranks a
 * draft re-open, and the durable dispatched trace outranks both.
 */
internal enum class HandoffSheetMode { DRAFT, RESUME, DISPATCHED }

/** The mode the sheet state carries; DRAFT is the fallthrough (fresh open). */
internal fun handoffSheetMode(state: HandoffSheetState): HandoffSheetMode = when {
    state.approvedRow != null -> HandoffSheetMode.RESUME
    state.dispatchedRow != null -> HandoffSheetMode.DISPATCHED
    else -> HandoffSheetMode.DRAFT
}

/**
 * The summary stays editable ONLY in the fresh/draft modes. The approved
 * row's summary is exactly what the user approved - editing it would be a
 * new approval pretending to be the old one, so RESUME (and the durable
 * trace) show it read-only; a different plan is a new draft (the no-alias
 * rule's editing face, plan :416).
 */
internal fun handoffEditAllowed(mode: HandoffSheetMode): Boolean =
    mode == HandoffSheetMode.DRAFT

/**
 * Resume-mode Confirm semantics, named once for both the sheet's banner and
 * the ViewModel's branch: a selection that MATCHES the approved
 * destination continues the approved row (dispatch only, no new row); a
 * changed destination REPLACES the trace (abandon the approved row, mint a
 * fresh draft that this same Confirm approves - the old approval never
 * transfers, plan :416). A null selection means "no decision yet"; the
 * Confirm gate keeps that shape from reaching the ViewModel anyway.
 */
internal fun handoffResumeReplacingTarget(
    approved: HandoffEntity,
    selectedDestination: String?,
): Boolean = selectedDestination != null && selectedDestination != approved.targetProjectId

/**
 * A display-ready stamp for the RESUME banner (the row's `userApprovedAt`),
 * the device zone + Locale.US digits - the same formatting discipline
 * [handoffSourceStamp] documents (a UTC string would mislead about when the
 * approval happened). <= 0 never reaches a real APPROVED row's stamp; the
 * callers guard the null before calling.
 */
internal fun handoffApprovalStamp(approvedAtMs: Long): String =
    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)
        .withZone(java.time.ZoneId.systemDefault())
        .format(java.time.Instant.ofEpochMilli(approvedAtMs))

/**
 * The engine session id is a long opaque token; the surfaces that name a
 * session in prose (the DISPATCHED banner) show a short prefix + ellipsis -
 * full ids pasted into copy, not prose. First characters keep sort order and
 * enough to match the drawer by eye.
 */
internal fun handoffSessionShort(id: String): String = id.take(8) + "…"

/**
 * The handoff row's provider stamp split into a MODEL id when it can be
 * one: the stamp carries "model id else provider id" (see
 * [handoffProviderStamp]), so a stamp that IS a provider id denotes no
 * model choice and returns null - the instance's own selection stands.
 * Pure: the provider-id universe is injected, keeping the contract
 * testable without the registry.
 */
internal fun handoffStampAsModel(stamp: String, providerIds: Set<String>): String? =
    stamp.trim().takeIf { it.isNotEmpty() && !providerIds.contains(it) }

/**
 * The replay-surviving resume affordance (the D+F drive's UX
 * enhancement), classified pure: when the ACTIVE source chat's newest
 * handoff row is DISPATCHED and the user is NOT already viewing the
 * target Code session, the bar shows. Built FROM THE ROW - the table
 * truth the ephemeral dispatch note (view state) can never survive a
 * replay with. The caller resolves the label (the projects row may be
 * gone; its honest fallback lives there).
 *
 * [targetSessionIdOf] extracts the jump target so room-shape drift
 * cannot buy a wrong view; a row with no target is not a hint.
 */
internal fun handoffResumeHint(
    rows: List<HandoffEntity>,
    activeSessionId: String?,
    targetSessionIdOf: (HandoffEntity) -> String?,
): HandoffResumeHint? {
    if (activeSessionId == null) return null
    val row = rows.firstOrNull { it.status == "DISPATCHED" } ?: return null
    val target = targetSessionIdOf(row) ?: return null
    if (target == activeSessionId) return null
    return HandoffResumeHint(
        handoffId = row.handoffId,
        targetSessionId = target,
        targetLabel = "",
    )
}

/**
 * The resume bar's state: the minimum the jump needs plus the label the
 * ViewModel resolves. [targetLabel] is the ADR-0001 project row's display
 * name, or the ViewModel's honest "its project" when that row is gone.
 */
data class HandoffResumeHint(
    val handoffId: String,
    val targetSessionId: String,
    val targetLabel: String,
)

/**
 * The app's best-known "which provider/model served the source chat"
 * stamp (the row's NOT NULL column and the first turn's provenance
 * parameter): the model id when a live selection exists, else the provider
 * id. Its exact value origin remains the build plan's §12 owner fact 2;
 * this is what the app can READ today - the same declared-not-fabricated
 * posture the engine-visible model id filter took.
 */
internal fun handoffProviderStamp(providerId: String, selectedModel: String): String =
    selectedModel.trim().takeIf { it.isNotEmpty() } ?: providerId

/**
 * The dispatch session's title, plan §Batch E (e1): "Handoff <HHmmss>".
 * The clock format rides the same formatter precedent the message-save
 * name uses (successive dispatches do not silently collide).
 */
internal fun handoffSessionTitle(at: LocalTime): String =
    "Handoff " + at.format(DateTimeFormatter.ofPattern("HHmmss", Locale.US))

/**
 * A caller-ready timestamp string from the session list's epoch-seconds
 * stamp, for the first turn's provenance slot. Formatted in the device's
 * own zone (a provenance time in UTC would mislead the user about when the
 * source exchange happened) with Locale.US pinning the digits - the
 * session list's money-string rationale applies to whole dates too (Lint
 * DefaultLocale). <= 0 is "the app knows no clock": null, which the
 * assembler renders as the plan's own "(not recorded)" wording.
 */
internal fun handoffSourceStamp(updatedAtSec: Long): String? {
    if (updatedAtSec <= 0L) return null
    return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)
        .withZone(java.time.ZoneId.systemDefault())
        .format(java.time.Instant.ofEpochSecond(updatedAtSec))
}

/**
 * The dispatch's transcript note - the S-4 note grammar ChatDenialNoteTest
 * established: a `role = "system"` row the Bubble renders as a centered
 * bodySmall line. READ: the note is view state - it is appended to the
 * on-screen list only, never into any engine transcript, and a later replay
 * of the target session replaces it. The note id derives from the handoffId,
 * so re-opens dedupe for exactly the same reason the S-4 denial note's id
 * derives from the turn id.
 */
internal fun handoffDispatchNote(handoffId: String, projectLabel: String): ChatMessage =
    ChatMessage(
        id = "p6-handoff-$handoffId",
        role = "system",
        text = "Handed off to $projectLabel.",
    )
