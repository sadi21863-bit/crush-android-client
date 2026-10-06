package com.opencode.chat.data.repository

import com.opencode.chat.data.local.SettingsDataStore
import com.opencode.chat.domain.model.AppSettings
import com.opencode.chat.domain.model.DeviceProfile
import com.opencode.chat.domain.model.PermissionMode
import com.opencode.chat.domain.model.TuningPolicy
import kotlinx.coroutines.flow.Flow

class SettingsRepository(private val dataStore: SettingsDataStore) {

    val settings: Flow<AppSettings> = dataStore.settingsFlow

    suspend fun saveApiKey(key: String) = dataStore.saveApiKey(key)
suspend fun clearApiKey() = dataStore.clearApiKey()
    suspend fun saveProviderId(id: String) = dataStore.saveProviderId(id)
    suspend fun saveSelectedModel(model: String) = dataStore.saveSelectedModel(model)
    suspend fun saveThemeMode(mode: String) = dataStore.saveThemeMode(mode)
    suspend fun saveEnterSendMode(mode: String) = dataStore.saveEnterSendMode(mode)
    suspend fun saveTemperature(temp: Double) = dataStore.saveTemperature(temp)

    /**
     * Persists how the agent's tool calls are authorised.
     *
     * Was already implemented in SettingsDataStore and called from nowhere, so
     * AUTO_APPROVE could not be selected and ASK was unreachable in the UI.
     * The write is the whole mechanism: ChatViewModel reads
     * AppSettings.permissionMode when it opens the workspace, so persisting is
     * the only thing this setting needs in order to take effect.
     */
    suspend fun savePermissionMode(mode: PermissionMode) =
        dataStore.savePermissionMode(mode)

    suspend fun saveTuningOverrides(o: TuningPolicy.Overrides) =
        dataStore.saveTuningOverrides(o)

    /**
     * The device's static capability profile, detected once. Exposed through the
     * repository so the settings UI can explain WHY auto chose each value,
     * which is the difference between a setting the user trusts and one they
     * override blindly.
     */
    val deviceProfile: DeviceProfile by lazy { DeviceProfile.detect(dataStore.context) }

    /** Auto-tuned values combined with the user's overrides. */
    fun resolvedTuning(settings: AppSettings): TuningPolicy.Resolved =
        TuningPolicy.resolve(deviceProfile, settings.tuning)
}
