package com.opencode.chat.data.local

import android.app.Activity
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.fragment.app.FragmentActivity
import com.opencode.chat.util.AppLog

/**
 * Bridges KeyguardManager's device-credential screen back to a callback.
 *
 * The credential screen is an Activity, not a dialog, so it cannot report
 * through BiometricPrompt's callback. It has to be launched for a result from a
 * host Activity, which means the result has to be routed back through
 * onActivityResult. That plumbing is process-wide singleton state on purpose: the
 * Activity that owns it can be recreated on rotation while the credential screen
 * is still up, and a launcher captured in the old instance would leak the
 * callback and leave the lock screen stuck.
 *
 * Only ONE credential prompt can be in flight; a second request while one is
 * active is rejected rather than silently overwriting the pending callback,
 * because losing that callback is exactly how the app ends up permanently locked.
 */
object DeviceCredentialPrompt {

    private const val REQUEST_CODE = 0x0C05

    private var pending: ((Boolean) -> Unit)? = null

    private var launcher: ActivityResultLauncher<Intent>? = null

    /** True when a credential screen is already showing. */
    val isActive: Boolean get() = pending != null

    /**
     * Registers the launcher. Safe to call on every Activity creation; the
     * previous registration is released first.
     */
    fun register(activity: FragmentActivity, launcher: ActivityResultLauncher<Intent>) {
        this.launcher?.unregister()
        this.launcher = launcher
    }

    /**
     * Launches the credential screen, or invokes [onResult] with false when one
     * is already in flight.
     */
    fun launch(activity: FragmentActivity, intent: Intent, onResult: (Boolean) -> Unit) {
        if (pending != null) {
            // A prompt is already up. Reporting failure here would surface as a
            // spurious "cancelled" on the newer caller, so refuse instead of
            // replacing the callback that the visible prompt will fire.
            AppLog.w("DeviceCredential", "prompt already active; ignoring duplicate request")
            return
        }
        pending = onResult
        val l = launcher
        if (l == null) {
            pending = null
            AppLog.w("DeviceCredential", "no launcher registered; treating as cancel")
            onResult(false)
            return
        }
        runCatching { l.launch(intent) }.onFailure {
            pending = null
            AppLog.e("DeviceCredential", "launch failed", it)
            onResult(false)
        }
    }

    /**
     * Delivers the credential result. Called from the registered
     * ActivityResultLauncher, which is why no request code is needed here.
     */
    fun deliverResult(resultCode: Int) {
        val cb = pending
        pending = null
        val ok = resultCode == Activity.RESULT_OK
        AppLog.i("DeviceCredential", "credential result ok=$ok")
        cb?.invoke(ok)
    }

    /**
     * For hosts that forward onActivityResult instead of using the launcher.
     * Returns true when the result was consumed.
     */
    fun handleActivityResult(requestCode: Int, resultCode: Int): Boolean {
        if (requestCode != REQUEST_CODE) return false
        deliverResult(resultCode)
        return true
    }

    /**
     * Fails any in-flight prompt. Call when the host Activity is destroyed so a
     * rotation cannot leave the lock screen waiting forever.
     */
    fun abandon() {
        pending?.invoke(false)
        pending = null
    }

    /** Test seam: the request code a host must forward if it uses onActivityResult. */
    const val code: Int = REQUEST_CODE
}