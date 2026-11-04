package com.crush.mobile.ui.screens.projects

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crush.mobile.di.AppContainer
import com.crush.mobile.data.project.RoomProjectStore
import com.crush.mobile.project.ProjectRef
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Projects surface's state holder (P3 slices 1–3, ADR-0001 accepted
 * 2026-11-03). Lists projects (the legacy workspace migrates in on entry),
 * binds chat on open, imports EXISTING folders, and removes rows.
 *
 * 2026-11-04 PRODUCT DECISION (user): Projects are created ONLY via Import
 * folder - the desktop-app model. The former minted-internal-repo creation
 * path ("New project", filesDir/projects/<uuid>/repo) is GONE: its content
 * was invisible to the user's file manager, which is precisely the failure
 * import-folder exists to fix (ADR-0001 2026-11-04decision block). The
 * store's `create` stays - it is the migration + import seam this VM calls.
 */
class ProjectsViewModel(
    private val appContext: Context,
    private val store: RoomProjectStore,
    private val container: AppContainer,
) : ViewModel() {

    data class UiState(
        val projects: List<ProjectRef> = emptyList(),
        val loading: Boolean = true,
        val busy: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        // The ADR's additive-migration invariant, exercised on entry: the
        // legacy filesDir/workspace appears as a row, idempotent by path, so
        // the surface starts truthful (the app's existing history belongs to
        // SOME project - the legacy one) instead of showing an empty list.
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    store.migrateLegacyWorkspace(File(appContext.filesDir, "workspace"))
                }
                refresh()
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = t.message ?: "registry init failed"
                )
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                val rows = withContext(Dispatchers.IO) { store.list() }
                _state.value = _state.value.copy(
                    projects = rows,
                    loading = false,
                    error = null
                )
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = t.message ?: "registry read failed"
                )
            }
        }
    }

    /**
     * Import an EXISTING folder as a project (user feature, 2026-11-04):
     * unlike the retired "New project" minting path the repo dir is not
     * created under filesDir - the
     * user points at a folder on shared storage (typically
     * /storage/emulated/0/<name>) and the project's workspace IS that real
     * folder, so the agent edits files in place, visible in any file
     * manager.
     *
     * Guards, in order: dedupe BY PATH first (the engine keys workspaces on
     * the path, so a twin row would be a second identity over one
     * workspace), then require REALLY-a-folder and REALLY-writable - the
     * engine child works the folder with the app's own UID, and if the app
     * cannot write it today the first agent turn would die on EACCES
     * instead (import fails early and legibly instead; the storage
     * permission request lives in the screen, which owns the pickers).
     */
    fun importFolderAsProject(displayLabel: String, rawPath: String) {
        val label = displayLabel.trim()
        val path = rawPath.trim().trimEnd('/')
        if (label.isEmpty() || path.isEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            try {
                withContext(Dispatchers.IO) {
                    val existing = store.byPath(path)
                    if (existing == null) {
                        val dir = File(path)
                        check(dir.isDirectory) { "not a folder as seen by the app: $path" }
                        check(dir.canWrite()) {
                            "folder is not writable yet (storage permission?): $path"
                        }
                        store.create(label, dir)
                    }
                    Unit
                }
                refresh()
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    error = t.message ?: "import failed"
                )
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    /**
     * Remove-from-list (2026-11-04 decision, user): type-honest semantics,
     * documented per row kind:
     *
     * - Imported (external) folder = USER DATA. The row is the app's only
     *   state; the folder and every file in it are untouched, forever.
     *   Re-importing the same folder later mints a fresh row id, so it
     *   starts a fresh project (fresh engine state dir + session pool).
     * - App-minted internal repo (filesDir/projects/...) = app-owned hidden
     *   data. Removing the project deletes that directory tree WITH the
     *   row - invisibility is exactly why the creation path was removed;
     *   keeping the tree would just accumulate unseeable junk.
     * - The LEGACY workspace row is refused (it hosts every existing chat;
     *   the confirmation dialog never even offers it, this is the VM-side
     *   double guard).
     *
     * Ordering: refuse first if a run is in flight on this row's engine
     * (never tear the floor from under in-progress work - the same rule the
     * container's stop-on-switch guards by), THEN stop the engine instance,
     * THEN drop the row, THEN delete the internal trees. The engine state
     * dir (filesDir/crush-<key>) is deleted for both kinds: it is app-owned
     * engine bookkeeping whose key can never recur (row ids are UUIDs), and
     * it must not orphan after the last reference dies.
     */
    fun removeProject(ref: ProjectRef) {
        val legacyPath = File(appContext.filesDir, "workspace").absolutePath
        if (ref.repoDir.absolutePath == legacyPath) return
        val key = container.instanceKeyFor(ref.id)
        val internalRoot = File(appContext.filesDir, "projects").absolutePath + File.separator
        val isInternal = ref.repoDir.absolutePath.startsWith(internalRoot)
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            try {
                withContext(Dispatchers.IO) {
                    check(container.engineRunInFlightFor != key) {
                        "a run is active on this project's engine - remove it " +
                            "after the run finishes"
                    }
                    container.forgetProjectEngine(ref.id)
                    store.remove(ref.id)
                    if (isInternal) {
                        // Delete the whole minted SLOT
                        // (files/projects/<short-uuid>), not just the repo dir
                        // inside it: the first build deleted ref.repoDir and
                        // left the empty wrapper behind (measured on device
                        // 2026-11-04). The slot is app-owned, invisible to the
                        // user's file manager, and its key can never recur.
                        ref.repoDir.parentFile?.deleteRecursively()
                    }
                    File(appContext.filesDir, "crush-$key").deleteRecursively()
                    Unit
                }
                refresh()
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    error = t.message ?: "remove failed"
                )
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    /**
     * Same shape as `ChatViewModel.Factory`: takes (context, container),
     * resolves the container's store itself. Used as
     * `viewModel(factory = ProjectsViewModel.Factory(context, container))`.
     */
    class Factory(
        private val appContext: Context,
        private val container: AppContainer,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            // application-scoped like every other container-held context.
            ProjectsViewModel(appContext.applicationContext, container.projectStore, container) as T
    }
}
