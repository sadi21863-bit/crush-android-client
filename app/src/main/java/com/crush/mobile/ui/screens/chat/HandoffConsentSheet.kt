package com.crush.mobile.ui.screens.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
 * Structure, per the batch brief:
 * - the EDITABLE summary composer (degrade law: empty is allowed and stays
 *   allowed - Confirm never blocks on an empty summary);
 * - the source provenance line (title + session id, non-editable);
 * - the destination picker: the Room projects-table rows, SINGLE select,
 *   with the "no project selected" default (Confirm stays disabled until
 *   one row is chosen);
 * - the consent card: what will run there, the fresh-session note, the
 *   no-alias statement, and the honesty hint that this is not a hands-free
 *   run (ask cards may still fire);
 * - [Cancel] (silent) / [Confirm] (enabled once a project is selected).
 */
private const val CONSENT_RUN_COPY =
    "A Code agent with file and shell tools will run in the chosen project."
private const val CONSENT_FRESH_COPY =
    "A fresh Code session is created for the handoff; the source chat stays as it is."
private const val CONSENT_NO_ALIAS_COPY =
    "Changing the destination after approval requires a new draft and a new approval - the approved target never changes in place."
private const val CONSENT_ASK_CARDS_COPY =
    "This does not promise a hands-free run: the agent's first turn may ask for permission through the usual ask cards."
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
    /** Cancel: silent, nothing was written (never-inserted mapping). */
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
        ) {
            Text(
                "Use this plan in Code",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "From \"${sheet.sourceChatTitle}\" (session ${sheet.sourceChatId ?: "not recorded"})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Plan summary",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = summary,
                onValueChange = onSummary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                minLines = 5,
                maxLines = 14,
                supportingText = { Text(SUMMARY_HINT) }
            )
            Text(
                "Summary text is the whole package: nothing from the chat rides along that you cannot already copy.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
            Spacer(Modifier.height(12.dp))
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
            Spacer(Modifier.height(12.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("Cancel")
                }
                Button(
                    onClick = onConfirm,
                    // The Confirm gate: a destination must be chosen; the
                    // summary's emptiness is never a blocker (degrade law).
                    enabled = handoffConfirmEnabled(selectedDestination),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Confirm")
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
