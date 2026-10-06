package com.opencode.chat.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.opencode.chat.data.repository.SettingsRepository
import com.opencode.chat.domain.model.AppSettings
import com.opencode.chat.domain.model.PermissionMode
import com.opencode.chat.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    /** Non-fatal problem worth showing, e.g. a failed Keystore write. */
    val error: String? = null
)

class SettingsViewModel(
    /** Exposed so the tuning section can read the device profile. */
    val settingsRepository: SettingsRepository
) : ViewModel() {

    val repo: SettingsRepository get() = settingsRepository

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _uiState.value = _uiState.value.copy(settings = settings)
            }
        }
    }

/**
     * Saves the key and REPORTS whether it worked.
     *
     * This must suspend and return a result rather than fire-and-forget:
     * an earlier version swallowed the failure inside its own launch{}, so the
     * caller's runCatching{}.isSuccess was always true and onboarding navigated
     * into chat with no key stored at all.
 *
     * An uncaught exception here also killed the process outright (no FATAL
     * EXCEPTION in logcat), so the failure must never escape.
     */
    suspend fun saveApiKeyAwait(key: String): Boolean =
        runCatching { settingsRepository.saveApiKey(key) }
            .onFailure {
                AppLog.e(TAG, "saveApiKey failed", it)
                _uiState.value = _uiState.value.copy(
                    error = "Could not save the API key. Unlock may have been cancelled."
                )
            }
            .onSuccess { _uiState.value = _uiState.value.copy(error = null) }
            .isSuccess

    fun saveApiKey(key: String) {
        viewModelScope.launch { saveApiKeyAwait(key) }
    }

    /**
     * Removes the stored credential.
     *
     * Also clears the in-memory copy in KeySession, otherwise the app would keep
     * serving a deleted key from the process heap until the next lock.
     */
    fun clearApiKey() {
        viewModelScope.launch {
            runCatching { settingsRepository.clearApiKey() }
                .onFailure {
                    AppLog.e(TAG, "clearApiKey failed", it)
                    _uiState.value = _uiState.value.copy(error = "Could not delete the key.")
                }
                .onSuccess { _uiState.value = _uiState.value.copy(error = null) }
        }
    }

    fun saveThemeMode(mode: String) {
        viewModelScope.launch { settingsRepository.saveThemeMode(mode) }
    }

fun saveEnterSendMode(mode: String) {
        viewModelScope.launch { settingsRepository.saveEnterSendMode(mode) }
    }

    /**
     * Persists the agent's permission mode.
     *
     * Mirrors [saveThemeMode] exactly, including the absence of error
     * reporting: the value flows back through the settings flow, so the switch
     * snaps back if the write does not land. Adding the failure plumbing here
     * alone would be a change to how every other setting behaves too.
     *
     * The value is read by ChatViewModel when it opens a workspace, so this
     * changes the NEXT agent start - not a session already in flight.
     */
    fun savePermissionMode(mode: PermissionMode) {
        viewModelScope.launch { settingsRepository.savePermissionMode(mode) }
    }

/**
     * Drops any previous failure so the next attempt starts from a clean slate.
     *
     * The UI calls this when a save begins, not when it ends. That ordering is
     * what stops a stale error surviving a later SUCCESSFUL save, and it also
     * guarantees the error transitions null -> message on every failed retry, so
     * the screen's LaunchedEffect fires again even when two failures in a row
     * produce the identical string (a StateFlow does not re-emit an equal value).
     */
    fun dismissError() {
        if (_uiState.value.error != null) {
            _uiState.value = _uiState.value.copy(error = null)
        }
    }

/** Coroutine scope for the tuning rows' fire-and-forget saves. */
    val scope: kotlinx.coroutines.CoroutineScope get() = viewModelScope

    private companion object {
        const val TAG = "SettingsVM"
    }

    class Factory(
        private val settingsRepository: SettingsRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(settingsRepository) as T
        }
    }
}

