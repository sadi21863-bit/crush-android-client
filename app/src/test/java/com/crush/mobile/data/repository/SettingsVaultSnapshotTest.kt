package com.crush.mobile.data.repository

import android.content.Context
import com.crush.mobile.data.local.KeySession
import com.crush.mobile.data.local.SettingsDataStore
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import com.crush.mobile.data.local.SecureKeyStore

/**
 * The settings layer's vault-snapshot seam (2026-11-01).
 *
 * [SettingsRepository.providersWithKeys] backs the per-provider "key stored"
 * badges: the settings screen must report slot presence for EVERY provider,
 * while the settings flow itself only ever describes the ACTIVE provider's
 * key. This pins that the snapshot crosses the repository boundary whole and
 * stays metadata-only.
 *
 * No cipher is involved: slots are seeded as bare ct/iv prefs entries, which
 * is exactly what [SecureKeyStore.providersWithKeys] reads (a scan of the
 * prefs key names). The real Keystore cipher stays on-device - see
 * MainActivityLockFlowTest, which exercises the real store including this
 * method on the reference device.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsVaultSnapshotTest {

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private val prefs
        get() = context.getSharedPreferences(SecureKeyStore.PREFS, Context.MODE_PRIVATE)

    private fun repo(): SettingsRepository =
        SettingsRepository(SettingsDataStore(context, KeySession()))

    private fun seed(providerId: String) {
        prefs.edit()
            .putString("${SecureKeyStore.KEY_CIPHERTEXT}.$providerId", "seed-ct")
            .putString("${SecureKeyStore.KEY_IV}.$providerId", "seed-iv")
            .commit()
    }

    @Test
    fun `the snapshot reports every seeded slot`() {
        seed("hyper")
        seed("opencode-go")
        seed("openrouter")

        assertEquals(
            setOf("hyper", "opencode-go", "openrouter"),
            repo().providersWithKeys()
        )
    }

    @Test
    fun `an empty vault reports empty without touching the cipher`() {
        prefs.edit().clear().commit()

        assertEquals(emptySet<String>(), repo().providersWithKeys())
    }

    @Test
    fun `the snapshot ignores non-ciphertext prefs entries`() {
        // The same prefs file also stores tuning/comment metadata under other
        // prefixes; only `ct.<id>` names may claim a stored key.
        prefs.edit()
            .putString("${SecureKeyStore.KEY_IV}.hyper", "seed-iv")
            .putString("tuning.something", "x")
            .putString("${SecureKeyStore.KEY_CIPHERTEXT}.hyper", "seed-ct")
            .commit()

        assertEquals(setOf("hyper"), repo().providersWithKeys())
    }
}
