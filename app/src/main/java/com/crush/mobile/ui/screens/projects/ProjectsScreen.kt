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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crush.mobile.CrushMobileApp
import com.crush.mobile.data.project.PendingProjectTrust
import com.crush.mobile.project.FolderUriDecoder
import java.io.File

/**
 * The Projects surface's HOST (P3 slices 1–3; Phase 2b.2 extraction).
 *
 * It owns the things a content layer must not: the ViewModel, the container,
 * the Context-derived paths, and the system permission/SAF launchers. The
 * render tree lives in [ProjectsContent], which receives only values and
 * callbacks.
 *
 * The screen signature is deliberately unchanged (`onBack`, `onOpenProject`):
 * callers and navigation do not know the seam exists.
 */
@Composable
fun ProjectsScreen(onBack: () -> Unit, onOpenProject: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val container = (context.applicationContext as CrushMobileApp).container
    val vm: ProjectsViewModel = viewModel(factory = ProjectsViewModel.Factory(context, container))
    val s by vm.state.collectAsStateWithLifecycle()
    var importError by remember { mutableStateOf<String?>(null) }

    // S4/F04 (acceptance A05) + S-02: an imported folder MAY carry a
    // project-root `crushrc` (or its `.crushrc` dotfile), which the embedded
    // engine EXECUTES AS BASH on every workspace attach (S1b measured: twice
    // per cycle, with the app's full permissions). Import therefore
    // implicitly includes arbitrary shell execution unless the user
    // consents. The gate is DELIBERATE and CENTRAL (ProjectTrustService): the
    // host holds the row's pending DECISION - ref + shaped reason - while the
    // service owns the verdicts; the cold-restore equivalent of this state
    // lives on the container (`pendingProjectTrust`) but in the same dialog,
    // so the wording can never fork between the two surfaces.
    var pendingCrushrcTrust by remember { mutableStateOf<PendingProjectTrust?>(null) }

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
        val mappedPath = FolderUriDecoder.primaryPathFromTreeDocumentId(docId)
        val path = FolderUriDecoder.primaryProjectPathFromTreeDocumentId(docId)
        when {
            mappedPath == FolderUriDecoder.PRIMARY_ROOT -> {
                // Importing the volume root would turn every file on the phone
                // into a workspace; a project is a folder the user chose.
                importError =
                    "Pick a project folder, not the root of internal storage."
            }
            path == null -> {
                importError =
                    "That location has no real folder path on this phone - " +
                        "please pick a folder in internal storage."
            }
            else -> {
                importError = null
                vm.importFolderAsProject(FolderUriDecoder.displayNameFor(path), path)
            }
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

    // Explain All Files Access BEFORE sending the user to the system screen.
    // The request is just-in-time (only when importing a project), never on
    // launch, and the copy is deliberately honest about the grant being
    // device-wide: the app-level boundary is the imported folder, not a
    // sandbox the OS enforces.
    var afaRationaleOpen by remember { mutableStateOf(false) }

    val importClick = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                pickTreeFolder.launch(null)
            } else {
                afaRationaleOpen = true
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

    if (afaRationaleOpen) {
        AlertDialog(
            onDismissRequest = { afaRationaleOpen = false },
            title = { Text("All files access, explained") },
            text = {
                Text(
                    "Crush runs a native coding engine inside the folder you " +
                        "import. Android cannot give a native process a scoped " +
                        "SAF folder, so the engine needs All files access to " +
                        "edit that folder in place.\n\n" +
                        "Android grants the permission device-wide. Crush uses " +
                        "it only for the project folder you choose: it does " +
                        "not browse, index, or scan your other files.\n\n" +
                        "On the next screen, turn on All files access for " +
                        "Crush, then return here."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        afaRationaleOpen = false
                        // Re-checked here (not just in importClick) so lint sees
                        // the API-30-gated constant inside its own guard, and so
                        // this dialog can never launch the intent on API 29.
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            askAllFilesAccess.launch(intent)
                        }
                    }
                ) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = { afaRationaleOpen = false }) {
                    Text("Not now")
                }
            }
        )
    }

    val legacyPath = File(context.filesDir, "workspace").absolutePath
    val internalRoot = File(context.filesDir, "projects").absolutePath + File.separator

    ProjectsContent(
        state = s,
        legacyPath = legacyPath,
        internalRoot = internalRoot,
        importError = importError,
        pendingTrust = pendingCrushrcTrust,
        onBack = onBack,
        onImport = importClick,
        onOpen = { project ->
            when {
                project.repoDir.absolutePath == legacyPath -> {
                    container.setActiveProject(null)
                    onOpenProject()
                }
                else ->
                    // S-02: the row tap goes through the central gate BEFORE
                    // anything binds. Allow binds + opens; Required parks the
                    // decision here for the dialog.
                    vm.requestOpenProject(
                        ref = project,
                        onAllowed = onOpenProject,
                        onTrustRequired = { pending ->
                            pendingCrushrcTrust = pending
                        },
                    )
            }
        },
        onRemove = vm::removeProject,
        onTrustConfirm = {
            val target = pendingCrushrcTrust
            pendingCrushrcTrust = null
            if (target != null) {
                vm.grantTrustAndOpen(target.ref, onOpen = onOpenProject)
            }
        },
        onTrustDismiss = { pendingCrushrcTrust = null },
    )
}
