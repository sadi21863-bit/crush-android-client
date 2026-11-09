package com.crush.mobile.ui.screens.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.crush.mobile.project.ProjectRef

/**
 * The Chat->Code handoff consent sheet (P6 Batch C, plan §Batch C (c3) in
 * the session-drawer's `ModalBottomSheet` grammar, drawn beside the model
 * picker sheet).
 *
 * FULLY STATE-HOISTED (the composable holds no `remember` of its own): the
 * caller owns the selection and the edited summary, and Confirm carries
 * BOTH back as parameters. That hoisting is the composable-contract shape
 * the no-alias rule needs - every Confirm is a fresh decision the
 * ViewModel can only express as a fresh draft/package seal; there is no
 * sheet-held target state that could ever ride an already-approved row
 * (plan :416 - "If the target project changes, require new confirmation;
 * no dynamic target alias").
 *
 * Structure, per the batch briefs (Batch C shape, Batch D modes):
 * - [HandoffSheetMode.DRAFT] is Batch C's sheet: the EDITABLE summary composer
 *   (degrade law: empty is allowed and stays allowed - Confirm never blocks on
 *   an empty summary), the source provenance line, the destination picker
 *   (single select, "no project selected" default), the consent card, and
 *   [Cancel] (silent) / [Confirm] (enabled once a project is chosen);
 * - [HandoffSheetMode.RESUME] re-opens a resumable APPROVED row (Batch D): the
 *   approved summary READ-ONLY (what was approved is what dispatches), the
 *   approved destination preselected by the caller, Confirm relabeled
 *   "Dispatch" (the guarded re-use of the existing approval), the no-alias
 *   banner when the selection changes, and the explicit "Discard" affordance
 *   (the abandon is a decision, never a dismiss);
 * - [HandoffSheetMode.DISPATCHED] is the durable trace's jump surface (Batch
 *   D): no picker, no editable summary, Cancel + "Open in Code", and NO
 *   discard - the trace cannot be abandoned (the DAO guard refuses it anyway).
 *
 * Still no `remember` of its own: the caller owns the selection and the
 * edited summary in every mode, so every Confirm remains a fresh decision.
 */
private const val CONSENT_RUN_COPY =
    "A Code agent with file and shell tools will run in the chosen project."
private const val CONSENT_FRESH_COPY =
    "A fresh Code session is created for the handoff; the source chat stays as it is."
private const val CONSENT_NO_ALIAS_COPY =
    "Changing the destination after approval requires a new draft and a new approval - the approved target never changes in place."
private const val CONSENT_ASK_CARDS_COPY =
    "This does not promise a hands-free run: the agent's first turn may ask for permission through the usual ask cards."
private const val CONSENT_RESUME_COPY =
    "This handoff was already approved - Confirm dispatches on that approval, no new approval is written."
private const val RESUME_LOCKED_HINT =
    "The approved summary - read-only; a new draft re-approves a plan."
private const val DISPATCHED_TRACE_COPY =
    "This chat's plan already runs in Code - the handoff trace is durable."
private const val DISPATCHED_JUMP_COPY =
    "Re-open its session below; the trace row cannot be discarded or re-approved here."
private const val SUMMARY_HINT =
    "Editable - empty is allowed and stays allowed."

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HandoffConsentSheet(
    sheet: HandoffSheetState,
    /** The destination rows: the projects table as the picker's only source. */
    projects: List<ProjectRef>,
    /** The chosen ADR-0001 row id, or null (the no-project default). */
    selectedDestination: String?,
    /** The user's edited summary (empty allowed - the degrade law). */
    summary: String,
    onSummary: (String) -> Unit,
    /** Single-select at a time; tapping another row REPLACES the choice. */
    onSelect: (String) -> Unit,
    /** Whispered only when a destination is chosen - see handoffConfirmEnabled. */
    onConfirm: () -> Unit,
    /** Cancel: silent in EVERY mode - discard is the only explicit abandon. */
    onDismiss: () -> Unit,
    /** The explicit abandon move (HandoffDao.abandon); rendered only where a
     *  written row rides the sheet (DRAFT or RESUME slot), never DISPATCHED. */
    onDiscard: () -> Unit,
    /** The DISPATCHED-mode jump to the trace's target Code session. */
    onOpenInCode: () -> Unit,
) {
    val mode = handoffSheetMode(sheet)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
        ) {
            Text(
                if (mode == HandoffSheetMode.DISPATCHED) "Handoff in Code" else "Use this plan in Code",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "From \"${sheet.sourceChatTitle}\" (session ${sheet.sourceChatId ?: "not recorded"})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
            // Mode banner (Batch D): the RESUME and DISPATCHED surfaces tell
            // the user what their row already carries before any picker;
            // the fresh open shows none.
            sheet.approvedRow?.takeIf { mode == HandoffSheetMode.RESUME }?.let { row ->
                val replacing = handoffResumeReplacingTarget(row, selectedDestination)
                val label = if (replacing) {
                    projects.firstOrNull { it.id.toString() == selectedDestination }?.label
                        ?: "the new project"
                } else {
                    projects.firstOrNull { it.id.toString() == row.targetProjectId }?.label
                        ?: "the approved project"
                }
                Text(
                    CONSENT_RESUME_COPY + " Approved " +
                        handoffApprovalStamp(row.userApprovedAt ?: 0L) + ".",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Text(
                    if (!replacing) "Dispatching to \"$label\" on the existing approval."
                    else CONSENT_NO_ALIAS_COPY,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            sheet.dispatchedRow?.takeIf { mode == HandoffSheetMode.DISPATCHED }?.let { row ->
                val label = projects.firstOrNull { it.id.toString() == row.targetProjectId }?.label
                    ?: "its project"
                Text(
                    DISPATCHED_TRACE_COPY,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Text(
                    "Session ${handoffSessionShort(row.targetCodeSessionId ?: "")} in \"$label\".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Text(
                    DISPATCHED_JUMP_COPY,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Plan summary",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = summary,
                onValueChange = onSummary,
                // The approved summary is read-only in RESUME/DISPATCHED:
                // editing it would be a NEW approval wearing the old one's
                // approval stamp; a different plan is a new draft (plan :416).
                enabled = handoffEditAllowed(mode),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                minLines = 5,
                maxLines = 14,
                supportingText = {
                    Text(if (handoffEditAllowed(mode)) SUMMARY_HINT else RESUME_LOCKED_HINT)
                }
            )
            Text(
                "Summary text is the whole package: nothing from the chat rides along that you cannot already copy.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
            Spacer(Modifier.height(12.dp))
            if (mode == HandoffSheetMode.DISPATCHED) {
                // The trace's destination is FIXED - the no-alias rule in its
                // purest form: no picker is offered at all (plan :416).
                val label = sheet.dispatchedRow?.let { row ->
                    projects.firstOrNull { it.id.toString() == row.targetProjectId }?.label
                } ?: "its approved project"
                Text(
                    "Destination",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Runs in \"$label\" (the approved destination).",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
            } else {
                Text(
                    "Destination",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (projects.isEmpty()) {
                    Text(
                        "No projects yet - create one on the Projects screen first.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                } else {
                    Spacer(Modifier.height(4.dp))
                    projects.forEach { ref ->
                        DestinationRow(
                            ref = ref,
                            selected = ref.id.toString() == selectedDestination,
                            onSelect = { onSelect(ref.id.toString()) }
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                val chosenRef = projects.firstOrNull { it.id.toString() == selectedDestination }
                Text(
                    when {
                        selectedDestination == null -> "No project selected"
                        chosenRef != null -> "Will run in \"${chosenRef.label}\""
                        else -> "The chosen project row is gone - pick another destination."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    when (mode) {
                        HandoffSheetMode.DRAFT -> {
                            Text(
                                CONSENT_RUN_COPY,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                CONSENT_FRESH_COPY,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                CONSENT_NO_ALIAS_COPY,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                CONSENT_ASK_CARDS_COPY,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        HandoffSheetMode.RESUME -> {
                            Text(
                                CONSENT_RUN_COPY,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                CONSENT_NO_ALIAS_COPY,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                CONSENT_ASK_CARDS_COPY,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        HandoffSheetMode.DISPATCHED -> {
                            Text(
                                DISPATCHED_TRACE_COPY,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                DISPATCHED_JUMP_COPY,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("Cancel")
                }
                if (mode == HandoffSheetMode.DISPATCHED) {
                    Button(
                        onClick = onOpenInCode,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Open in Code")
                    }
                } else {
                    Button(
                        onClick = onConfirm,
                        // The Confirm gate: a destination must be chosen; the
                        // summary's emptiness is never a blocker (degrade law
                        // - and in RESUME the approved summary stands in).
                        enabled = handoffConfirmEnabled(selectedDestination),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (mode == HandoffSheetMode.RESUME) "Dispatch" else "Confirm")
                    }
                }
            }
            // The explicit discard (Batch D): abandon via HandoffDao.abandon
            // is a decision the user makes, never a dismiss. DISPATCHED rows
            // offer nothing here - the DAO guard refuses them and the trace
            // is the proof-row-(b) surface.
            if (sheet.existingDraft != null || sheet.approvedRow != null) {
                TextButton(
                    onClick = onDiscard,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text(
                        "Discard this handoff",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * One destination row (the picker's whole surface): label + repo path +
 * visible selected state. The color-carrying Surface has no click behavior
 * of its own (the session drawer's P2 lesson) - the Row inside is the only
 * tap target.
 */
@Composable
private fun DestinationRow(
    ref: ProjectRef,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelect)
                .semantics { stateDescription = if (selected) "selected" else "not selected" }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    ref.label,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    ref.repoDir.absolutePath,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (selected) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
    Spacer(Modifier.height(6.dp))
}
