package com.opencode.chat.ui.screens.lock

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.opencode.chat.data.local.DeviceAuth
import com.opencode.chat.data.local.KeySession
import com.opencode.chat.data.local.SecureKeyStore
import com.opencode.chat.ui.AppLockPolicy
import com.opencode.chat.util.AppLog

private const val TAG = "LockOverlay"

/**
 * Full-screen gate shown over the app while it is locked.
 *
 * This is a Compose overlay rather than a navigation destination on purpose. The
 * navigation graph must stay composed underneath so scroll position, a
 * half-typed draft, and the back stack all survive a lock/unlock cycle. Adding a
 * Lock destination would have thrown all of that away every time the user
 * switched apps.
 *
 * Unlocking uses [DeviceAuth], which is platform-only. It deliberately does not
 * use androidx.biometric: that library requires an AppCompat theme and crashed
 * onboarding on an API 30+ device, which was the entire reason the key stopped
 * being auth-gated in the first place.
 */
@Composable
fun LockOverlay(
    store: SecureKeyStore,
    keySession: KeySession,
    onUnlocked: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activity = LocalContext.current as? FragmentActivity
    var attempts by remember { mutableIntStateOf(0) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun runAuth(auto: Boolean) {
        val host = activity
        if (host == null || busy) return
        busy = true
        errorText = null

        DeviceAuth.authenticate(
            activity = host,
            title = "Unlock OpenCode",
            subtitle = "Your chats and API key are stored on this device",
            onResult = { ok ->
                busy = false
                if (!ok) {
                    // Cancelling is a legitimate answer, not an error worth
                    // shouting about.
                    errorText = "Authentication cancelled."
                    return@authenticate
                }
                // Reading is unconditional now that the key is encrypted but not
                // auth-gated, so this either yields the key or storage is broken.
                val key = runCatching { store.read() }.getOrNull().orEmpty()
                if (key.isBlank()) {
                    AppLog.w(TAG, "unlocked but no key could be read")
                    errorText = "Could not read your saved key."
                    return@authenticate
                }
                keySession.put(key)
                AppLog.i(TAG, "unlocked")
                onUnlocked()
            }
        )
        if (auto) attempts++
    }

    // Auto-prompt on entry so the common case is a single glance. Capped so a
    // cancel cannot become a loop: the prompt returns immediately on cancel, so
    // an uncapped effect keyed on gate state would re-open it forever.
    LaunchedEffect(Unit) {
        if (AppLockPolicy.shouldAutoPrompt(attempts)) runAuth(auto = true)
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Locked",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Authenticate to open your chats.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = { runAuth(auto = false) },
                enabled = !busy && activity != null
            ) {
                Text(if (busy) "Waiting for authentication" else "Unlock")
            }
            if (activity == null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Cannot prompt for authentication in this host.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }
            errorText?.let {
                Spacer(Modifier.height(24.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
