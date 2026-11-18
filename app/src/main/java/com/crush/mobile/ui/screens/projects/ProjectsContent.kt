package com.crush.mobile.ui.screens.projects

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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.crush.mobile.data.project.PendingProjectTrust
import com.crush.mobile.project.ProjectRef
import com.crush.mobile.ui.CrushrcTrustDialog
import com.crush.mobile.ui.crushrcTrustNote

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
    onBack: () -> Unit,
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
                title = { Text("Projects") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
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
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            importError?.let { err ->
                Text(
                    err,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            Spacer(Modifier.height(12.dp))

            state.error?.let { err ->
                Text(
                    "Registry error: $err",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            when {
                state.loading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 16.dp)
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Loading projects...")
                }

                state.projects.isEmpty() -> Text(
                    // Say WHY, per the house rule on empty states.
                    "No projects yet - the first entry here should always be the " +
                        "legacy workspace (the app's existing chat history).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp)
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
                        val removable = project.repoDir.absolutePath != legacyPath
                        ProjectRow(
                            project,
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
                style = MaterialTheme.typography.bodySmall,
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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectRow(project: ProjectRef, onOpen: () -> Unit, onRemove: (() -> Unit)? = null) {
    Card(onClick = onOpen, Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.FolderOpen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    project.label,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                if (onRemove != null) {
                    IconButton(onClick = onRemove) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Remove ${project.label}"
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                project.repoDir.absolutePath,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "${project.environmentLevel.name} workspace",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
