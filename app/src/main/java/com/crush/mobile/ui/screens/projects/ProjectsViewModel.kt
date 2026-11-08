package com.crush.mobile.ui.screens.projects

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crush.mobile.di.AppContainer
import com.crush.mobile.data.project.PendingProjectTrust
import com.crush.mobile.data.project.RoomProjectStore
import com.crush.mobile.data.project.TrustVerdict
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
     *
     * S-02 (2026-11-05): the F04 consent state lives ON the project row
     * itself (RoomProjectStore/ProjectTrustSource), so the tombstone below
     * IS the trust-record clear - no separate step exists, and the guard
     * order stays untouched by construction. A removed row's consent cannot
     * resurrect: re-importing the same folder mints a fresh row id that
     * starts with NO record, which is exactly the fresh-consent contract.
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
     * S-02 (2026-11-05): the row-open path through the CENTRAL gate. The
     * screen calls this for every non-legacy row tap; the placement of the
     * gate BEFORE any binding/callers-callback is the load-bearing bit (the
     * restore-equivalent trap was binding first and asking never).
     *
     * - Allow → the gate-OBSERVED state is re-recorded, but ONLY if the
     *   folder still shows exactly what the gate saw: a crushrc that
     *   appears or changes inside the gate->record window writes nothing
     *   and the open is treated EXACTLY like Required below. Otherwise,
     *   ONLY THEN the bind, then [onAllowed] opens chat.
     * - Required → NOTHING binds; [onTrustRequired] hands the screen the
     *   pending decision (ref + shaped reason) and the F04 dialog takes
     *   over. Consent, when given, flows through [grantTrustAndOpen].
     * - A trust-state read failure flows to s.error (same channel a failed
     *   registry read uses) and binds nothing.
     *
     * The gate+record+open sequence rides the same busy flag the import and
     * remove paths use (the reads went async with the gate; an overlapping
     * double-tap must not fire two verdicts).
     */
    fun requestOpenProject(
        ref: ProjectRef,
        onAllowed: () -> Unit,
        onTrustRequired: (PendingProjectTrust) -> Unit,
    ) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            try {
                val verdict = runCatching { container.projectTrust.gate(ref) }
                    .getOrElse {
                        _state.value = _state.value.copy(
                            error = "trust check failed: ${it.message ?: "unreadable consent state"}"
                        )
                        return@launch
                    }
                when (verdict) {
                    is TrustVerdict.Allow -> {
                        // Record BEFORE bind, but ONLY the state this Allow
                        // vouched for: confirmAtGate writes NOTHING when the
                        // folder moved inside the gate->record window - a
                        // crushrc that appears or swaps there can never ride
                        // in on this Allow's consent.
                        val outcome = runCatching {
                            container.projectTrust.confirmAtGate(ref, verdict.observedFingerprint)
                        }.getOrElse {
                            _state.value = _state.value.copy(
                                error = "consent could not be recorded: " +
                                    (it.message ?: "unknown store failure")
                            )
                            return@launch
                        }
                        val movedTo = outcome.movedTo
                        if (movedTo == null) {
                            // The screen has taken over (or consumed) this project's
                            // pending restore prompt, if one existed; leaving it up
                            // would double-ask the user for the same folder.
                            container.clearPendingProjectTrust()
                            container.setActiveProject(ref)
                            onAllowed()
                        } else {
                            // The folder changed between gate and record:
                            // NOTHING was persisted, so this open is treated
                            // EXACTLY like a Required verdict - the F04 dialog
                            // takes over and the row re-gates at the next tap.
                            container.clearPendingProjectTrust()
                            onTrustRequired(PendingProjectTrust(ref, movedTo))
                        }
                    }
                    is TrustVerdict.Required -> {
                        container.clearPendingProjectTrust()
                        onTrustRequired(PendingProjectTrust(ref, verdict.kind))
                    }
                }
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    /**
     * S-02 consent path from the F04 dialog ("Run it and open"): record
     * consent for the CURRENT bytes (the service re-hashes at record time,
     * so an approval can never be persisted for a stale fingerprint), bind,
     * and only then open chat. A record failure is fail-closed: error in the
     * screen channel, no bind, no navigation - the user retries with the
     * dialog gone (the row re-gates at the next tap, nothing lost).
     */
    fun grantTrustAndOpen(ref: ProjectRef, onOpen: () -> Unit) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            try {
                container.projectTrust.recordConsent(ref)
                container.clearPendingProjectTrust()
                container.setActiveProject(ref)
                onOpen()
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    error = t.message ?: "consent could not be recorded"
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
