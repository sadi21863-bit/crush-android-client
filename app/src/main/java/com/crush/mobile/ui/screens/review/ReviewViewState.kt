package com.crush.mobile.ui.screens.review

import com.crush.mobile.domain.file.TurnChanges

/**
 * Phase 2b.3 (V2 plan §§5.6, 6.5): the Review surface's inert view state.
 *
 * The content layer renders from this and two callbacks; the host keeps the
 * container-scoped [com.crush.mobile.domain.file.ReviewHistory], the coroutine
 * scope, the SAF export launcher, and the file I/O. No Android type crosses the
 * seam.
 */
data class ReviewUiState(
    /** Newest first, as the history store publishes it. */
    val cards: List<TurnChanges> = emptyList(),
    val runInFlight: Boolean = false,
    /** The last export outcome as one sentence; null = no message. */
    val exportMessage: String? = null,
)
