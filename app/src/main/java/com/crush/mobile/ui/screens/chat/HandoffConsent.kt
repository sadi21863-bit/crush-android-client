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
    /** A still-touched DRAFT row for this source chat, or null. */
    val existingDraft: HandoffEntity? = null,
) {
    /**
     * The summary the sheet's composer initialises: the user's own
     * persisted draft text beats the machine prefill whenever the row is
     * still untouched. Empty in the degraded open and STAYS editable -
     * the degrade law (design §4(1), plan b3) is that empty is allowed
     * and never blocks Confirm.
     */
    val initialSummary: String
        get() = existingDraft?.summaryMarkdown ?: draft.summaryMarkdown

    /** Display provenance: the source session's title as the app knows it. */
    val sourceChatTitle: String get() = draft.sourceChatTitle

    /**
     * The composer-borne source chat id; null refuses the seal at Confirm
     * (honest notice, no row), never a fabricated engine reference.
     */
    val sourceChatId: String? get() = draft.sourceChatId

    /**
     * Attaches the current-draft lookup's row (Batch A's
     * `latestDraftForSource`), the re-open its own contract describes: the
     * user's untouched draft is what the composer opens with, never a
     * minted twin.
     */
    fun withExisting(entity: HandoffEntity): HandoffSheetState =
        copy(existingDraft = entity)
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
