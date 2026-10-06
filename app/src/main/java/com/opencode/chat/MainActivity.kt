package com.opencode.chat

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
import com.opencode.chat.domain.model.AppSettings
import com.opencode.chat.engine.EngineCompatibility
import com.opencode.chat.ui.navigation.NavGraph
import com.opencode.chat.ui.screens.lock.LockOverlay
import com.opencode.chat.ui.AppLockPolicy
import com.opencode.chat.ui.StartRoute
import com.opencode.chat.ui.theme.OpenCodeChatTheme
import com.opencode.chat.data.local.SecureKeyStore
import com.opencode.chat.data.local.DeviceCredentialPrompt
import com.opencode.chat.util.AppLog
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
            text = "This phone can't run OpenCode Chat",
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

        val container = (application as OpenCodeChatApp).container

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

            OpenCodeChatTheme(themeMode = settings.themeMode) {
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
                    val hasApiKey = remember(store) { store.hasStoredCiphertext() }

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
                                hasApiKey = hasApiKey
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
                                onUnlocked = {
                                    // The gate IS the unlocked flag. An earlier
                                    // version kept a separate latched boolean that
                                    // stayed true after the first unlock, so the
                                    // app never locked again.
                                    gateState.value = AppLockPolicy.Gate.OPEN
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
        val container = (application as OpenCodeChatApp).container
        val hasCiphertext = store.hasStoredCiphertext()
        // Captured BEFORE the reset below. Logging after it meant every line read
        // "coldStart=false", so the log could not distinguish a cold launch from
        // a resume - which is precisely the distinction being debugged.
        val wasColdStart = coldStart
        val awaySec = pausedAtElapsedMs?.let {
            (SystemClock.elapsedRealtime() - it) / 1000L
        }
        val gate = AppLockPolicy.decide(
            hasCiphertext = hasCiphertext,
            alreadyUnlocked = container.keySession.isUnlocked,
            isColdStart = wasColdStart,
            secondsSinceBackground = awaySec,
            graceSeconds = LOCK_GRACE_SEC
        )
        // Age the in-memory key on PAUSE, not on resume.
        //
        // Clearing it in onResume when the gate came back PROMPT was subtly
        // wrong: AppLockPolicy can also return PROMPT while a key IS held (long
        // absence, unobserved pause), and clearing then discarded a perfectly
        // good key and forced a pointless prompt. Ageing it at pause time means
        // "isUnlocked" is already honest by the time onResume asks.
        if (awaySec != null && awaySec >= LOCK_GRACE_SEC) container.keySession.clear()
        coldStart = false
        // Dropping the in-memory key on lock is the whole point of the gate.
        if (gate == AppLockPolicy.Gate.PROMPT) container.keySession.clear()
        gateState.value = gate
        AppLog.i(
            "Main",
            "resume gate=$gate coldStart=$wasColdStart away=${awaySec ?: -1}s " +
                "ciphertext=$hasCiphertext wasUnlocked=${container.keySession.isUnlocked}"
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
         * Matches the Keystore auth window in SecureKeyStore. Below this the Cipher
         * still works, so prompting would be friction without security value.
         * Set the manual lock setting to 0 for strict prompt-every-resume.
         */
        const val LOCK_GRACE_SEC = 300L
    }
}