package com.opencode.chat.data.local

import android.app.KeyguardManager
import android.content.Context
import android.util.Log
import androidx.fragment.app.FragmentActivity
import com.opencode.chat.util.AppLog

/**
 * Unlocks the app using ONLY the platform credential screen.
 *
 * Deliberately ONE code path for every supported API level, 29 through 36.
 *
 * The two obvious alternatives were both tried and both caused problems:
 *
 *  - androidx.biometric requires an APP-COMPAT theme. This app used the platform
 *    Material theme, so authenticating on API 30+ threw and killed onboarding on
 *    a friend's OnePlus Nord. API 29 hid it because that path takes a different
 *    internal branch.
 *
 *  - android.hardware.biometrics.BiometricPrompt needs its nested PromptInfo and
 *    Authenticators types, which do not resolve cleanly at this compileSdk and
 *    reintroduce a version branch to maintain.
 *
 * KeyguardManager.createConfirmDeviceCredentialIntent() has none of those
 * problems: it is public API from 23 onward, needs no theme, no third-party
 * library, and on API 30+ the system screen itself offers the fingerprint
 * sensor as the fast path. One path means one thing to test, and the only
 * version-dependent behaviour is what the user sees, all of it drawn by the OS.
 *
 * Nothing in here may throw: an unlock that cannot be displayed must degrade to
 * a cancelled unlock, never a process death.
 */
object DeviceAuth {

    private const val TAG = "DeviceAuth"

    /**
     * Prompts the user. [onResult] is always invoked exactly once.
     *
     * [onResult] true means the user proved who they are; callers still have to
     * read the key. False means cancelled, which is an ordinary outcome and not
     * an error to report as one.
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onResult: (Boolean) -> Unit
    ) {
        try {
            val km = activity.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            val intent = km?.createConfirmDeviceCredentialIntent(title, subtitle)
            if (intent == null) {
                // No secure lock screen configured, so there is nothing to
                // authenticate against. Allow through and let the caller decide:
                // the app lock is a convenience for the person holding the phone,
                // not the thing protecting the key at rest.
                AppLog.w(TAG, "no secure lock screen; allowing without prompt")
                onResult(true)
                return
            }
            DeviceCredentialPrompt.launch(activity, intent, onResult)
        } catch (t: Throwable) {
            // A prompt that cannot start must not take the app down.
            Log.e(TAG, "unlock failed to start", t)
            AppLog.e(TAG, "unlock threw: ${t.javaClass.simpleName}", t)
            onResult(false)
        }
    }

    /**
     * Whether a secure lock screen exists at all.
     *
     * Used to warn rather than silently allow: on a device with no PIN, pattern
     * or password the app cannot lock itself, and the user should be told that
     * rather than discovering it.
     */
    fun hasSecureLockScreen(context: Context): Boolean = runCatching {
        (context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)
            ?.isDeviceSecure == true
    }.getOrDefault(false)

    /** Test seam: the request code a host must forward if it uses onActivityResult. */
    const val code: Int = DeviceCredentialPrompt.code
}
