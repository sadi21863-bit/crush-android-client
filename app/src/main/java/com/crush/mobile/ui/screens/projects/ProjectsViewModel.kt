package com.crush.mobile.ui.screens.projects

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crush.mobile.di.AppContainer
import com.crush.mobile.data.project.RoomProjectStore
import com.crush.mobile.project.ProjectRef
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Projects surface's state holder (P3 slice 1, ADR-0001 accepted
 * 2026-11-03). Slice 1 is the STORE's first in-app reader: list, create, and
 * the idempotent legacy-workspace migration on entry. There is deliberately
 * NO chat binding yet — opening a project against its OWN engine instance
 * (the accepted ADR's per-instance shape) is slice 2, and no row here
 * pretends to be tappable-into-chat until that lands.
 */
class ProjectsViewModel(
    private val appContext: Context,
    private val store: RoomProjectStore,
) : ViewModel() {

    data class UiState(
        val projects: List<ProjectRef> = emptyList(),
        val loading: Boolean = true,
        val busy: Boolean = false,
        val showCreate: Boolean = false,
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

    fun showCreateDialog() {
        _state.value = _state.value.copy(showCreate = true, error = null)
    }

    fun dismissCreateDialog() {
        _state.value = _state.value.copy(showCreate = false)
    }

    fun createProject(rawLabel: String) {
        val label = rawLabel.trim()
        if (label.isEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, showCreate = false)
            try {
                // Canonical cwd per the ADR: one directory under
                // filesDir/projects/<short-uuid>/repo, stable for the row's
                // lifetime since the ENGINE keys the workspace on this path.
                withContext(Dispatchers.IO) {
                    store.create(
                        label,
                        File(
                            appContext.filesDir,
                            "projects/${UUID.randomUUID().toString().substring(0, 8)}/repo"
                        )
                    )
                }
                refresh()
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    busy = false,
                    error = t.message ?: "create failed"
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
            ProjectsViewModel(appContext, container.projectStore) as T
    }
}
