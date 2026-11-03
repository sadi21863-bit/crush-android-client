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
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crush.mobile.CrushMobileApp
import com.crush.mobile.project.ProjectRef

/**
 * The Projects surface (P3 slice 1): the project registry's in-app reader.
 * Lists projects (the legacy workspace migrates in on first entry), creates
 * new ones with an app-private canonical cwd. Deliberately NOT clickable into
 * a chat yet — per the accepted ADR, a project's session isolation lives on
 * its OWN engine instance, and that binding is slice 2. Saying so in the UI
 * (see the footer note) is part of honesty-in-UI, not an excuse.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val container = (context.applicationContext as CrushMobileApp).container
    val vm: ProjectsViewModel = viewModel(factory = ProjectsViewModel.Factory(context, container))
    val s by vm.state.collectAsStateWithLifecycle()
    var draftLabel by remember { mutableStateOf("") }

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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = vm::showCreateDialog, enabled = !s.busy) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("New project")
                }
            }
            Spacer(Modifier.height(12.dp))

            s.error?.let { err ->
                Text(
                    "Registry error: $err",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            when {
                s.loading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 16.dp)
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Loading projects...")
                }

                s.projects.isEmpty() -> Text(
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
                    items(s.projects, key = { it.id }) { project ->
                        ProjectRow(project)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "Slice-1 note: projects are stored and listed here; opening one " +
                    "in chat (its own engine instance) lands in the next slice.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }
    }

    if (s.showCreate) {
        AlertDialog(
            onDismissRequest = vm::dismissCreateDialog,
            title = { Text("New project") },
            text = {
                Column {
                    Text(
                        "A private directory is created for this project's files; " +
                            "its chat sessions live on their own engine instance.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = draftLabel,
                        onValueChange = { draftLabel = it },
                        label = { Text("Name") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = ImeAction.Done
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { vm.createProject(draftLabel); draftLabel = "" },
                    enabled = draftLabel.isNotBlank() && !s.busy
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissCreateDialog) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ProjectRow(project: ProjectRef) {
    Card(Modifier.fillMaxWidth()) {
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
                    style = MaterialTheme.typography.titleMedium
                )
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
