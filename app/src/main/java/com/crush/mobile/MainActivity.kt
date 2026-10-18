package com.crush.mobile

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.crush.mobile.domain.model.AppSettings
import com.crush.mobile.engine.EngineCompatibility
import com.crush.mobile.ui.navigation.NavGraph
import com.crush.mobile.ui.screens.lock.LockOverlay
import com.crush.mobile.ui.AppLockPolicy
import com.crush.mobile.ui.StartRoute
import com.crush.mobile.ui.theme.CrushMobileTheme
import com.crush.mobile.data.local.SecureKeyStore
import com.crush.mobile.data.local.DeviceCredentialPrompt
import com.crush.mobile.util.AppLog
import java.io.File

/**
 * Terminal screen for a device that cannot run the bundled engine.
 *
 * WHY A WHOLE SCREEN and not a banner: there is nothing left to show. The
 * engine IS the app - without it there is no chat, no model picker, and no
 * setting that changes anything. Rendering navigation anyway would be the
 * "engine failed" experience in disguise: a UI that looks alive and is not.
 *
 * The copy is [EngineCompatibility.Result.Unsupported.reason], which is written
 * for a person rather than an engineer - it names the actual cause (a 32-bit
 * CPU) and says what would work instead.
 */
@Composable
private fun EngineUnsupportedScreen(
    reason: String,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "This phone can't run Crush Mobile",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = reason,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onRetry) {
            Text("Check again")
        }
    }
}

/**
 * Hosts the app and owns the lock gate.
 *
 * The gate is an overlay, not a route, so backgrounding and unlocking the app
 * does not reset the navigation stack or discard a half-typed message.
 *
 * [onPause] records a monotonic timestamp and [onResume] hands the elapsed time
 * to [AppLockPolicy]. Wall-clock time is deliberately avoided: an NTP correction
 * or a manual clock change must not be able to fake a short absence and skip the
 * lock.
 */
class MainActivity : androidx.fragment.app.FragmentActivity() {

    private lateinit var credentialLauncher: ActivityResultLauncher<Intent>

    private var pausedAtElapsedMs: Long? = null
    private var coldStart = true

    /**
     * When the lock overlay last completed a successful authentication,
     * on the same monotonic clock [pausedAtElapsedMs] uses.
     *
     * The double-prompt bug this anchors: the overlay unlocks (`keySession.put`
     * + gate OPEN), then [onResume] fires with a large `secondsSinceBackground`
     * (the pause predates the fingerprint interaction by definition - the overlay
     * was shown BECAUSE of the long absence). The old code aged the key per that
     * timer, so it cleared the key the auth had JUST established and re-prompted.
     * A successful auth is user presence INDEPENDENT of how long the app was
     * away, so an unlock timestamped after the last pause must suppress the
     * absence timer for that resume. Compared against [pausedAtElapsedMs], not
     * wall-clock, for the reason in the class doc.
     */
    private var lastUnlockElapsedMs: Long? = null

    /**
     * One store for the whole Activity. `needsUnlock` is instance state, so a
     * per-recomposition instance reported whatever that instance last did rather
     * than what actually happened.
     */
    private val store: SecureKeyStore by lazy { SecureKeyStore(this) }

    private val gateState = mutableStateOf(AppLockPolicy.Gate.OPEN)

    /**
     * Human-facing reason the bundled engine cannot run here, or null when it can.
     *
     * Held as state rather than a local so "Check again" can flip it: the ABI is
     * fixed, but `hasBundledEngine` is not - the native library is extracted at
     * install and its presence is the one input a retry could genuinely change.
     *
     * Evaluated in onCreate, never in composition. The inputs are one
     * File.exists() and a constant array from Build, so recomputing per frame
     * would be I/O for a value that cannot vary between frames.
     */
    private var engineUnsupportedReason by mutableStateOf<String?>(null)

    /**
     * Asks [EngineCompatibility] whether the engine can run at all.
     *
     * The alternative - letting the user into chat and reporting the failure
     * there - is the worst available outcome: the app opens, looks completely
     * normal, and "engine failed" means nothing to someone who does not know an
     * engine exists. Refusing to pretend is the whole point of this check.
     */
    private fun checkEngineCompatibility() {
        // Checked against the app's nativeLibraryDir, which is where AGP extracts
        // the engine when useLegacyPackaging is on. Typed against android.content.Context
        // explicitly because the implicit applicationContext is a platform Context.
        // Read the location from applicationInfo, not from Context.nativeLibraryDir.
        // EnginePaths already resolves the engine this way (EnginePaths.kt:36), and
        // matching it means this check cannot disagree with the code that will
        // actually try to exec the binary.
        val hasBundledEngine =
            File(applicationInfo.nativeLibraryDir, ENGINE_BINARY).exists()
        val reason =
            (EngineCompatibility.evaluateDevice(hasBundledEngine)
                as? EngineCompatibility.Result.Unsupported)?.reason
        engineUnsupportedReason = reason
        if (reason != null) {
            AppLog.w("Main", "engine cannot run: $reason")
        } else {
            AppLog.i("Main", "engine compatible (bundled=$hasBundledEngine)")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.i("Main", "onCreate")

        val container = (application as CrushMobileApp).container

        // Routes the system PIN/pattern/password screen result back to the
        // pending unlock. Registered here because the credential screen is an
        // Activity, not a dialog, so it cannot report through a callback.
        credentialLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            DeviceCredentialPrompt.deliverResult(result.resultCode)
        }
        DeviceCredentialPrompt.register(this, credentialLauncher)

        // Before setContent, so the very first frame is already the decision
        // rather than a frame of chat that has to be taken back.
        checkEngineCompatibility()

        setContent {
            val settings by container.settingsRepository.settings.collectAsState(
                initial = AppSettings()
            )

            CrushMobileTheme(themeMode = settings.themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // One store instance for the whole Activity.
                    val store = this.store
                    val navController = rememberNavController()

                    // Routing keys off ciphertext presence alone. The key is no
                    // longer auth-gated, so reading it cannot fail for want of a
                    // prompt - there is exactly one reason to be here: either a
                    // key is stored or one is not. Reading it before the user
                    // unlocks would defeat the app lock, so we deliberately do not.
                    //
                    // Scoped to the SELECTED provider, and recomputed when it
                    // changes: on this screen the question is "can the chat for
                    // this provider start?", not "is any credential stored?" -
                    // the latter would route a user with only a Zen key into a
                    // chat that then reports "no API key" for Hyper.
                    val hasApiKey = remember(store, settings.providerId) {
                        store.hasStoredCiphertext(settings.providerId)
                    }

                    Box(Modifier.fillMaxSize()) {
                        // NavGraph is the ONLY thing that reaches ChatScreen,
                        // and ChatScreen is the only thing that calls
                        // engineSupervisor.start(). Not composing it here is what
                        // actually prevents the chat flow from starting - not
                        // just hiding it behind an error.
                        val unsupported = engineUnsupportedReason
                        if (unsupported != null) {
                            EngineUnsupportedScreen(
                                reason = unsupported,
                                onRetry = { checkEngineCompatibility() }
                            )
                        } else {
                            NavGraph(
                                navController = navController,
                                hasApiKey = hasApiKey,
                                // The chat tree must know the gate is closed, not
                                // just render behind the overlay.
                                isLocked = gateState.value == AppLockPolicy.Gate.PROMPT
                            )
                        }
                        // Deliberately OUTSIDE the branch above. The lock gate is
                        // not a compatibility concern: a locked app must not
                        // start acting because the platform check happened to
                        // fail. The overlay is shown over the compatibility
                        // message, and unlocking simply reveals it again.
                        if (gateState.value == AppLockPolicy.Gate.PROMPT) {
                            LockOverlay(
                                store = store,
                                keySession = container.keySession,
                                providerId = settings.providerId,
                                onUnlocked = {
                                    // The gate IS the unlocked flag. An earlier
                                    // version kept a separate latched boolean that
                                    // stayed true after the first unlock, so the
                                    // app never locked again.
                                    gateState.value = AppLockPolicy.Gate.OPEN
                                    // Stamp presence so the imminent onResume
                                    // (which fires after this) honours the fresh
                                    // auth instead of ageing the key away.
                                    lastUnlockElapsedMs = SystemClock.elapsedRealtime()
                                    // Let the chat layer retry now that a key is
                                    // actually in hand.
                                    container.notifyKeyAvailable()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        pausedAtElapsedMs = SystemClock.elapsedRealtime()
    }

    override fun onResume() {
        super.onResume()
        val container = (application as CrushMobileApp).container
        // ANY stored credential, not just the selected provider's. A key for a
        // provider the user is not currently using is still a secret at rest, so
        // it must still sit behind the gate. Scoping this to one provider would
        // leave an at-rest credential after a provider switch unguarded.
        //
        // The unlock flow tolerates the selected provider having no key, so this
        // cannot trap the user in the overlay; see LockOverlay.
        val hasCiphertext = store.providersWithKeys().isNotEmpty()
        // Captured BEFORE the reset below. Logging after it meant every line read
        // "coldStart=false", so the log could not distinguish a cold launch from
        // a resume - which is precisely the distinction being debugged.
        val wasColdStart = coldStart
        val awaySec = pausedAtElapsedMs?.let {
            (SystemClock.elapsedRealtime() - it) / 1000L
        }
        // An auth that completed at-or-after the last pause is fresh user
        // presence: the overlay's fingerprint/PIN interaction IS the "user is
        // here" fact the absence timer exists to approximate. Restart the trust
        // age at 0 for this resume so the key established by that auth is
        // neither cleared nor re-prompted. It must be 0 rather than null:
        // null means "unobserved pause" in [AppLockPolicy.decide] and fails
        // CLOSED, which would re-prompt anyway. With a 0 age the trust restarts
        // exactly as [AppLockPolicy] documents for the key it is told is in
        // hand; a LOCK_GRACE_SEC of 0 (strict mode) still prompts every time.
        // Paused-at versus unlock are compared on the elapsed clock; a null
        // pause cannot have preceded an unlock, so treat it as fresh too.
        val freshUnlock = lastUnlockElapsedMs != null &&
            (pausedAtElapsedMs == null || lastUnlockElapsedMs!! >= pausedAtElapsedMs!!)
        val gate = AppLockPolicy.decide(
            hasCiphertext = hasCiphertext,
            alreadyUnlocked = container.keySession.isUnlocked,
            isColdStart = wasColdStart,
            secondsSinceBackground = if (freshUnlock) 0L else awaySec,
            graceSeconds = LOCK_GRACE_SEC
        )
        // Age the in-memory key on PAUSE, not on resume.
        //
        // Clearing it in onResume when the gate came back PROMPT was subtly
        // wrong: AppLockPolicy can also return PROMPT while a key IS held (long
        // absence, unobserved pause), and clearing then discarded a perfectly
        // good key and forced a pointless prompt. Ageing it at pause time means
        // "isUnlocked" is already honest by the time onResume asks.
        if (!freshUnlock && awaySec != null && awaySec >= LOCK_GRACE_SEC) {
            container.keySession.clear()
        }
        coldStart = false
        // Dropping the in-memory key on lock is the whole point of the gate.
        if (gate == AppLockPolicy.Gate.PROMPT) container.keySession.clear()
        gateState.value = gate
        AppLog.i(
            "Main",
            "resume gate=$gate coldStart=$wasColdStart away=${awaySec ?: -1}s " +
                "ciphertext=$hasCiphertext wasUnlocked=${container.keySession.isUnlocked} " +
                "freshUnlock=$freshUnlock"
        )
    }

    private companion object {
        /**
         * File name of the bundled engine as it appears in nativeLibraryDir.
         *
         * Duplicated from EnginePaths on purpose: that class's constructor
         * mkdir()s four directories, and the question being asked here is a
         * yes/no one that must not have side effects. Kept in sync by the
         * single jniLibs/abi decision documented on
         * [EngineCompatibility.BUNDLED_ABI].
         */
        const val ENGINE_BINARY = "libcrush.so"

        /**
         * How long the in-memory key survives backgrounding.
         *
         * WAS described as matching a Keystore auth window. It does not, and
         * cannot: there is no auth window any more. `SecureKeyStore` no longer
         * gates its key on authentication - removing that gate fixed an API 30+
         * onboarding crash - so `read()` always succeeds and a keystore read proves
         * nothing about whether the user is present.
         *
         * So this is purely a UX trade: a brief glance away and back should not
         * cost a biometric prompt. It provides NO protection for the key at rest,
         * which is decryptable whether or not this timer has lapsed.
         *
         * Set to 0 for strict prompt-on-every-resume.
         */
        const val LOCK_GRACE_SEC = 300L
    }
}