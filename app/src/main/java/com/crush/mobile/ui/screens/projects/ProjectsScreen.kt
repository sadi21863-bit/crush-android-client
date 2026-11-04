package com.crush.mobile.ui.screens.projects

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crush.mobile.CrushMobileApp
import com.crush.mobile.project.FolderUriDecoder
import com.crush.mobile.project.ProjectRef

/**
 * The Projects surface (P3 slices 1–3): the project registry's in-app
 * reader. Lists projects (the legacy workspace migrates in on first entry),
 * and — per the 2026-11-04 decision — creates them ONLY by importing an
 * existing user-visible folder (the desktop-app model); the former
 * "New project" minted-internal-repo path is gone, and rows carry a
 * type-honest Remove affordance. Opening binds the chat to the row's OWN
 * engine instance (slice 2).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(onBack: () -> Unit, onOpenProject: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val container = (context.applicationContext as CrushMobileApp).container
    val vm: ProjectsViewModel = viewModel(factory = ProjectsViewModel.Factory(context, container))
    val s by vm.state.collectAsStateWithLifecycle()
    var importError by remember { mutableStateOf<String?>(null) }
    // The row a Remove tap came from, held while the confirmation dialog
    // explains what removal will (and will not) delete; null = no pending
    // confirmation.
    var pendingRemove by remember { mutableStateOf<ProjectRef?>(null) }

    // "Import folder as project" (user feature, 2026-11-04) - three launchers,
    // composed: the SAF folder picker, the storage-permission request that
    // guards it on API <= Q (this build's minimum), and the All-Files-Access
    // settings screen that the same flow needs on API >= R. On R+ a
    // content:// grant does NOT give the ENGINE child real filesystem access,
    // so the flow refuses to open the picker until the user granted the
    // manager-level permission; the decoded path is then handed to the
    // Model, which re-checks writability before creating the row.
    val pickTreeFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        val path = FolderUriDecoder.primaryPathFromTreeDocumentId(docId)
        if (path == null) {
            importError =
                "That location has no real folder path on this phone - " +
                    "please pick a folder in internal storage."
        } else {
            importError = null
            vm.importFolderAsProject(FolderUriDecoder.displayNameFor(path), path)
        }
    }

    val storagePermissions = arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
    )

    val askStoragePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (storagePermissions.all { grants[it] == true }) {
            importError = null
            pickTreeFolder.launch(null)
        } else {
            importError =
                "Storage permission was refused - importing a folder on this " +
                    "Android version cannot work without it."
        }
    }

    val askAllFilesAccess = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val granted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            Environment.isExternalStorageManager()
        if (granted) {
            importError = null
            pickTreeFolder.launch(null)
        } else {
            importError =
                "All-files access was not granted - importing a folder on this " +
                    "Android version cannot work without it."
        }
    }

    val importClick = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                pickTreeFolder.launch(null)
            } else {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                askAllFilesAccess.launch(intent)
            }
        } else {
            val missing = storagePermissions.filter {
                ContextCompat.checkSelfPermission(context, it) !=
                    PackageManager.PERMISSION_GRANTED
            }
            if (missing.isEmpty()) {
                pickTreeFolder.launch(null)
            } else {
                askStoragePermission.launch(missing.toTypedArray())
            }
        }
    }

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
            Button(onClick = importClick, enabled = !s.busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Import folder")
            }
            importError?.let { err ->
                Text(
                    err,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp)
                )
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
                        // Opening a row = binding the chat to that row's OWN
                        // engine instance (ADR-0001 slice 2). EXCEPTION: the
                        // legacy workspace row binds "" - the legacy workspace
                        // keeps its history on the unkeyed engine (the ADR's
                        // additive-migration invariant), it must never be
                        // re-instanced onto a fresh engine child. It likewise
                        // never gets a Remove affordance: it hosts every
                        // existing chat, and the entry migration would
                        // re-create its row anyway.
                        val legacyPath = java.io.File(
                            context.filesDir, "workspace"
                        ).absolutePath
                        val removable = project.repoDir.absolutePath != legacyPath
                        ProjectRow(
                            project,
                            onOpen = {
                                when (project.repoDir.absolutePath) {
                                    legacyPath -> container.setActiveProject(null)
                                    else -> container.setActiveProject(project)
                                }
                                onOpenProject()
                            },
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
        val internalRoot = java.io.File(
            context.filesDir, "projects"
        ).absolutePath + java.io.File.separator
        val internal = target.repoDir.absolutePath.startsWith(internalRoot)
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("Remove project") },
            text = {
                Text(
                    if (internal) {
                        "'%s' is an app-internal project. Removing it also " +
                            "DELETES its generated workspace folder %s " +
                            "permanently.".format(target.label, target.repoDir.absolutePath)
                    } else {
                        "Your folder %s and every file in it stay untouched - " +
                            "this only removes the app's reference. Re-importing " +
                            "the same folder later starts a fresh project."
                            .format(target.repoDir.absolutePath)
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { vm.removeProject(target); pendingRemove = null },
                    enabled = !s.busy
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) { Text("Cancel") }
            }
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
