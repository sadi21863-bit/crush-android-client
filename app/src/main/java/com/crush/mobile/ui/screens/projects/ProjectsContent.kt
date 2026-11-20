package com.crush.mobile.ui.screens.projects

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.crush.mobile.data.project.PendingProjectTrust
import com.crush.mobile.project.ProjectRef
import com.crush.mobile.ui.CrushrcTrustDialog
import com.crush.mobile.ui.components.CrushBanner
import com.crush.mobile.ui.components.CrushCard
import com.crush.mobile.ui.components.CrushEmptyState
import com.crush.mobile.ui.components.CrushLoadingRow
import com.crush.mobile.ui.components.CrushStatusLabel
import com.crush.mobile.ui.components.CrushTone
import com.crush.mobile.ui.crushrcTrustNote
import com.crush.mobile.ui.theme.CrushSpacing
import com.crush.mobile.ui.theme.CrushType
import com.crush.mobile.ui.theme.LocalCrushColors

/**
 * Phase 2b.2 (V2 plan §§5.6, 6.5): the Projects surface, rendered only from
 * the inert [ProjectsUiState] plus content-local view state.
 *
 * This composable may not read a Context, a ViewModel, the container, an
 * Activity, or a nav controller. [legacyPath] and [internalRoot] are
 * environment facts the host derives from `filesDir`; both are plain strings
 * here, so the row markup and the remove copy stay testable without Android
 * storage. Import permission flows (SAF, storage permission, All-files access)
 * stay in the host: they launch system UI and are not part of this surface's
 * render contract.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsContent(
    state: ProjectsUiState,
    legacyPath: String,
    internalRoot: String,
    importError: String?,
    pendingTrust: PendingProjectTrust?,
    onImport: () -> Unit,
    onOpen: (ProjectRef) -> Unit,
    onRemove: (ProjectRef) -> Unit,
    onTrustConfirm: () -> Unit,
    onTrustDismiss: () -> Unit,
) {
    // The row a Remove tap came from, held while the confirmation dialog
    // explains what removal will (and will not) delete; null = no pending
    // confirmation. Local to the content, exactly as the sheet/dialog state
    // stayed local in 2b.1.
    var pendingRemove by remember { mutableStateOf<ProjectRef?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Projects") }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // Creation is import-only (2026-11-04 decision): one button,
            // full width. Rows minted by the removed "New project" path can
            // still be removed individually via their row affordance.
            Button(onClick = onImport, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Import folder")
            }
            Text(
                "Crush edits the folder you choose in place. It does not browse " +
                    "or scan your other files.",
                style = CrushType.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            importError?.let { err ->
                CrushBanner(
                    text = err,
                    tone = CrushTone.Error,
                    modifier = Modifier.padding(top = CrushSpacing.sm)
                )
            }
            Spacer(Modifier.height(12.dp))

            state.error?.let { err ->
                CrushBanner(
                    text = "Projects error: $err",
                    tone = CrushTone.Error,
                    modifier = Modifier.padding(bottom = CrushSpacing.sm)
                )
            }

            when {
                state.loading -> CrushLoadingRow(
                    text = "Loading projects...",
                    modifier = Modifier.padding(vertical = CrushSpacing.lg)
                )

                state.projects.isEmpty() -> CrushEmptyState(
                    // Say WHY, per the house rule on empty states.
                    title = "No projects yet",
                    body = "Import a folder to create a project. The legacy " +
                        "workspace appears here after migration.",
                    modifier = Modifier.padding(vertical = CrushSpacing.md)
                )

                else -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(state.projects, key = { it.id }) { project ->
                        // Opening a row = binding the chat to that row's OWN
                        // engine instance (ADR-0001 slice 2). The legacy row
                        // is the host's routing exception; this layer only
                        // reports the tap. It likewise never gets a Remove
                        // affordance: it hosts every existing chat, and the
                        // entry migration would re-create its row anyway.
                        val isLegacy = project.repoDir.absolutePath == legacyPath
                        val removable = !isLegacy
                        // F-A/V2-5 D4: the current row is the container's
                        // active binding, not a remembered UI choice.
                        val current = project.id == state.activeProjectId ||
                            (isLegacy && state.legacyActive)
                        val identity = when {
                            isLegacy -> "App history workspace"
                            project.repoDir.absolutePath.startsWith(internalRoot) ->
                                "App-managed workspace"
                            else -> "Imported folder"
                        }
                        ProjectRow(
                            project = project,
                            current = current,
                            identityLabel = identity,
                            onOpen = { onOpen(project) },
                            onRemove = if (removable) {
                                { pendingRemove = project }
                            } else null
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "Tapping a project opens it in chat on its OWN engine instance; " +
                    "opening the legacy workspace returns to the shared engine.",
                style = CrushType.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }
    }

    // Removal confirmation (2026-11-04): the wording is type-honest so the
    // destructive case can never surprise the user - an imported folder's
    // confirmation says the folder is NOT touched; an app-minted internal
    // project's confirms that its hidden workspace tree is deleted.
    pendingRemove?.let { target ->
        val internal = target.repoDir.absolutePath.startsWith(internalRoot)
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("Remove project") },
            text = {
                Text(
                    if (internal) {
                        // Parentheses matter: .format must bind to the WHOLE
                        // concatenated literal, not the last piece - the
                        // un-parenthesized version measured on device
                        // 2026-11-04 printed literal placeholders ('%s') in
                        // the dialog instead of the project name/path.
                        ("'%s' is an app-internal project. Removing it also " +
                            "DELETES its generated workspace folder %s " +
                            "permanently.")
                            .format(target.label, target.repoDir.absolutePath)
                    } else {
                        ("Your folder %s and every file in it stay untouched - " +
                            "this only removes the app's reference. Re-importing " +
                            "the same folder later starts a fresh project.")
                            .format(target.repoDir.absolutePath)
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemove(target)
                        pendingRemove = null
                    },
                    enabled = !state.busy
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) { Text("Cancel") }
            }
        )
    }

    // F04 trust gate: open a crushrc-bearing folder ONLY after an explicit,
    // informed yes. Cancel = do not bind, no engine touched. The dialog is
    // the SHARED composable (`CrushrcTrustDialog`) so this surface and the
    // root-level restore prompt show byte-identical security copy; the
    // reason note is appended only for the re-approval shapes.
    pendingTrust?.let { target ->
        CrushrcTrustDialog(
            folderLabel = target.ref.label,
            reasonNote = crushrcTrustNote(target.kind),
            onConfirm = onTrustConfirm,
            onDismiss = onTrustDismiss,
        )
    }
}

/**
 * One registry row. [onRemove] is null for the legacy row (no Remove
 * affordance - it hosts the app's existing history and the entry migration
 * would resurrect it anyway).
 *
 * V2-5 D4/D5: the current row carries a filled check plus the word "Current"
 * and a spoken state; trust is a written status, never a colour-only badge.
 */
@Composable
private fun ProjectRow(
    project: ProjectRef,
    current: Boolean,
    identityLabel: String,
    onOpen: () -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    val colors = LocalCrushColors.current
    CrushCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics {
                if (current) stateDescription = "Current project"
            }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.FolderOpen,
                contentDescription = null,
                tint = colors.accent
            )
            Spacer(Modifier.width(CrushSpacing.sm))
            Text(
                text = project.label,
                style = CrushType.section,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (current) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(CrushSpacing.xs))
                Text("Current", style = CrushType.metadata, color = colors.muted)
            }
            if (onRemove != null) {
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Remove ${project.label}"
                    )
                }
            }
        }
        Spacer(Modifier.height(CrushSpacing.xs))
        Text(
            project.repoDir.absolutePath,
            style = CrushType.code,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(CrushSpacing.xs))
        Text(identityLabel, style = CrushType.metadata, color = colors.muted)
        Spacer(Modifier.height(CrushSpacing.xs))
        CrushStatusLabel(
            text = if (project.trusted) "Configuration approved"
            else "No approved configuration",
            tone = if (project.trusted) CrushTone.Success else CrushTone.Neutral,
        )
    }
}
