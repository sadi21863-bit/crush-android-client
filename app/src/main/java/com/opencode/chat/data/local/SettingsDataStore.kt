package com.opencode.chat.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.opencode.chat.domain.model.AppSettings
import com.opencode.chat.domain.provider.Providers
import com.opencode.chat.domain.model.PermissionMode
import com.opencode.chat.domain.model.TuningPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsDataStore(context: Context, private val keySession: KeySession = KeySession()) {

    /** Exposed so SettingsRepository can build the static device profile. */
    val context: Context = context.applicationContext

    /**
     * The API key never touches this DataStore. It lives in [SecureKeyStore] as
     * Keystore-backed ciphertext, so `settings.preferences_pb` stays safe to back
     * up and safe to read in a bug report.
     */
    private val secureKeys = SecureKeyStore(context)

    companion object {
        private val SELECTED_MODEL = stringPreferencesKey("selected_model")
        private val THEME_MODE = stringPreferencesKey("theme_mode")
        private val ENTER_SEND_MODE = stringPreferencesKey("enter_send_mode")
        private val TEMPERATURE = stringPreferencesKey("temperature")
        private val PROVIDER_ID = stringPreferencesKey("provider_id")
        private val PERMISSION_MODE = stringPreferencesKey("permission_mode")

        // Tuning overrides. Stored as empty string == "no override", which keeps
        // the AUTO vs MANUAL distinction the settings UI depends on.
        private val T_IDLE = stringPreferencesKey("tune_idle_timeout")
        private val T_HANG = stringPreferencesKey("tune_hang_grace")
        private val T_HEALTH = stringPreferencesKey("tune_health_interval")
        private val T_PREWARM = stringPreferencesKey("tune_prewarm")
        private val T_SESSIONS = stringPreferencesKey("tune_max_sessions")
    }

    // NOTE: receiver is String?, so inside takeIf the predicate parameter is
    // String? too and needs a null check.
    private fun String?.toIntOrNullOrNull(): Int? =
        this?.takeIf { it?.isNotBlank() == true }?.toIntOrNull()

    /** Prefs are stored as strings; "" means "no override". */
    private fun String?.toBoolOrNull(): Boolean? =
        this?.takeIf { it.isNotBlank() }?.toBooleanStrictOrNull()

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        // Deliberately does NOT put the key in AppSettings. It reports only that
        // one exists, plus a masked tail. Callers that genuinely need the
        // credential ask KeySession, which is the single place it is held.
        //
        // Resolved to the SAME id the chat layer uses, so the flag, the hint and
        // the stored slot always describe one provider. A raw persisted value
        // would disagree with the chat layer for anything this build no longer
        // knows - which is when "key saved" would show for a slot nothing reads.
        val providerId = Providers.resolve(prefs[PROVIDER_ID] ?: Providers.DEFAULT.id).id
        // Scoped peek: the in-memory cache holds ONE provider's key, and a hint
        // built from a different provider's key would describe a credential the
        // user is not looking at.
        val storedKey = keySession.peekFor(providerId)
        AppSettings(
            hasApiKey = secureKeys.hasStoredCiphertext(providerId),
            apiKeyHint = KeyMask.hint(storedKey),
            // No hardcoded fallback. A default model id is a guess, and this engine
            // rejects an unknown one at agent/init. The live catalogue decides;
            // see ModelChoice. An absent value just means "not chosen yet".
            selectedModel = prefs[SELECTED_MODEL].orEmpty(),
            themeMode = prefs[THEME_MODE] ?: "system",
            enterSendMode = prefs[ENTER_SEND_MODE] ?: "newline",
            temperature = prefs[TEMPERATURE]?.toDoubleOrNull() ?: 0.7,
            providerId = providerId,
            // parse() fails SAFE: an absent or corrupt value means ASK, never
            // auto-approve. A damaged preferences file must not be able to hand
            // the agent's permissions over silently.
            permissionMode = PermissionMode.parse(prefs[PERMISSION_MODE]),
            tuning = TuningPolicy.Overrides(
                engineIdleTimeoutSec = prefs[T_IDLE].toIntOrNullOrNull(),
                hangGraceSec = prefs[T_HANG].toIntOrNullOrNull(),
                healthIntervalSec = prefs[T_HEALTH].toIntOrNullOrNull(),
                prewarmEngine = prefs[T_PREWARM].toBoolOrNull(),
                maxRecentSessions = prefs[T_SESSIONS].toIntOrNullOrNull()
            )
        )
    }

    /**
     * Stores a user override. Passing null clears it and hands control back to
     * auto-tuning, which is what the "Reset to Auto" button does.
     */
    suspend fun saveTuningOverrides(o: TuningPolicy.Overrides) {
        context.dataStore.edit { p ->
            p[T_IDLE] = o.engineIdleTimeoutSec?.toString() ?: ""
            p[T_HANG] = o.hangGraceSec?.toString() ?: ""
            p[T_HEALTH] = o.healthIntervalSec?.toString() ?: ""
            p[T_PREWARM] = o.prewarmEngine?.toString() ?: ""
            p[T_SESSIONS] = o.maxRecentSessions?.toString() ?: ""
        }
    }

    /**
     * Stores a key for [providerId], never for "whatever provider is current".
     *
     * Provider-scoped on purpose. The store used to hold a single credential, so
     * saving a second provider's key destroyed the first and switching back left
     * the user with nothing - which is the normal case now that OpenCode Zen's
     * free tier is withdrawn and a second provider is the way to test at all.
     */
    suspend fun saveApiKey(key: String, providerId: String) {
        secureKeys.write(key, providerId)
        // Keep the in-memory copy in step, otherwise the very next settings
        // emission re-decrypts (or fails to) for a key we already hold.
        keySession.put(key, providerId)
    }

    /** Synchronous read for call sites that cannot suspend (engine bootstrap). */
    fun readApiKey(providerId: String): String = keySession.require(secureKeys, providerId)

    /** Removes ONE provider's credential, leaving the others intact. */
    suspend fun clearApiKey(providerId: String) {
        secureKeys.clear(providerId)
        keySession.clear()
    }

    suspend fun saveSelectedModel(model: String) {
        context.dataStore.edit { prefs -> prefs[SELECTED_MODEL] = model }
    }

    suspend fun saveThemeMode(mode: String) {
        context.dataStore.edit { prefs -> prefs[THEME_MODE] = mode }
    }

    suspend fun saveEnterSendMode(mode: String) {
        context.dataStore.edit { prefs -> prefs[ENTER_SEND_MODE] = mode }
    }

    suspend fun saveProviderId(id: String) {
        context.dataStore.edit { prefs -> prefs[PROVIDER_ID] = id }
    }

    suspend fun savePermissionMode(mode: PermissionMode) {
        context.dataStore.edit { prefs -> prefs[PERMISSION_MODE] = mode.stored }
    }

    suspend fun saveTemperature(temp: Double) {
        context.dataStore.edit { prefs -> prefs[TEMPERATURE] = temp.toString() }
    }
}
