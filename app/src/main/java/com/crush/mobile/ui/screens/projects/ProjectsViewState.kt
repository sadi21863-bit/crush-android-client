package com.crush.mobile.ui.screens.projects

import com.crush.mobile.project.ProjectRef

/**
 * Phase 2b.2 (V2 plan §§5.6, 6.5): the Projects surface's inert view state.
 *
 * Deliberately Android-free apart from the domain [ProjectRef] value: the
 * content layer renders from this and a handful of host-owned values, never
 * from the ViewModel, the container, or a Context. The host computes the
 * environment facts below (`legacyPath`, `internalRoot`) because only it owns
 * `filesDir`; the state itself stays pure data.
 */
data class ProjectsUiState(
    val projects: List<ProjectRef> = emptyList(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
)
