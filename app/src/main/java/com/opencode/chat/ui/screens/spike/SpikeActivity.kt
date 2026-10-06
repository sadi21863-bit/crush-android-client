package com.opencode.chat.ui.screens.spike

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import java.io.File
import com.opencode.chat.data.crush.WorkspaceManager
import com.opencode.chat.data.local.DeviceAuth
import com.opencode.chat.data.local.SecureKeyStore
import com.opencode.chat.data.crush.CrushSession
import com.opencode.chat.data.crush.CrushStreamProbe
import androidx.compose.ui.platform.LocalContext
import android.util.Log
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.opencode.chat.OpenCodeChatApp
import com.opencode.chat.data.crush.CrushApi
import com.opencode.chat.engine.EngineState
import com.opencode.chat.ui.theme.OpenCodeChatTheme

/**
 * Phase 0 diagnostic screen: boots the engine under the supervisor and reports
 * whether it serves HTTP on this device. Replaced by the real chat UI in Phase 4.
 */
// FragmentActivity (a ComponentActivity subclass) so BiometricPrompt can host
    // the unlock for the auth-gated Keystore key.
    class SpikeActivity : FragmentActivity() {
    // Set in onCreate. onNewIntent can arrive before onCreate on some launch
    // paths, so faults are queued rather than dropped.
    private var supervisor: com.opencode.chat.engine.EngineSupervisor? = null
    private var pendingFault: String? = null

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Faults must be deliverable to an ALREADY RUNNING app, otherwise we only
        // ever test the cold-start path and never a live session losing its engine.
        if (intent.action == "com.opencode.chat.FAULT") {
            val fault = intent.getStringExtra("fault")
            val s = supervisor
            if (s != null) runFault(fault, s) else pendingFault = fault
        }
    }

    private fun runFault(fault: String?, supervisor: com.opencode.chat.engine.EngineSupervisor) {
        when (fault) {
            "kill_engine" -> {
                supervisor.start()
                lifecycleScope.launch {
                    delay(2000)
                    val ok = supervisor.killEngineForFaultInjection()
                    Log.i("ChaosFault", "kill_engine signalled=$ok")
                }
            }
            // Asserts the supervisor does not spawn a second engine while
            // one is already alive.
            "double_start" -> {
                supervisor.start()
                lifecycleScope.launch {
                    delay(2000)
                    supervisor.start()
                    Log.i("ChaosFault", "double_start issued")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as OpenCodeChatApp).container
        val supervisor = container.engineSupervisor
        this.supervisor = supervisor

        // Fault-injection entry point for tools/chaos.ps1. See runFault().
        if (intent?.action == "com.opencode.chat.FAULT") {
            val f = intent.getStringExtra("fault")
            pendingFault?.let { runFault(it, supervisor); pendingFault = null }
                ?: runFault(f, supervisor)
        }

        setContent {
            OpenCodeChatTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SpikeScreen(
                        supervisor = supervisor,
                        binaryPath = container.crushEngine.binaryPath,
                        binaryUsable = container.crushEngine.isBinaryUsable
                    )
                }
            }
        }
    }
}

@Composable
private fun SpikeScreen(
    supervisor: com.opencode.chat.engine.EngineSupervisor,
    binaryPath: String,
    binaryUsable: Boolean
) {
    val state by supervisor.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val secureKeys = remember { com.opencode.chat.data.local.SecureKeyStore(ctx) }
    // RowScope shadows `this`, so resolve the host Activity explicitly for
    // BiometricPrompt.
    val hostActivity = ctx as? FragmentActivity

    DisposableEffect(Unit) {
        onDispose { supervisor.stop() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Crush Engine Spike", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Proves the bundled aarch64 engine can exec() and serve HTTP on this device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Binary", style = MaterialTheme.typography.labelMedium)
                Text(binaryPath, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Text(
                    if (binaryUsable) "EXISTS + EXECUTABLE" else "MISSING OR NOT EXECUTABLE",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (binaryUsable) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { supervisor.start() }) { Text("Start") }
            OutlinedButton(onClick = { supervisor.stop() }) { Text("Stop") }
        }

        // One ordered action. The bootstrap order is load-bearing:
        // workspace -> provider key -> agent/init. Doing agent/init first
        // returns HTTP 500 "coder agent configuration is missing".
        var apiKey by remember { mutableStateOf("") }
        var connState by remember { mutableStateOf("not connected") }
        var connBusy by remember { mutableStateOf(false) }
        var session by remember { mutableStateOf<CrushSession?>(null) }
        var prompt by remember { mutableStateOf("Reply with exactly: HELLO FROM CRUSH") }
        var reply by remember { mutableStateOf("") }
        var replyBusy by remember { mutableStateOf(false) }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Connect to OpenCode Zen", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = if (apiKey.isEmpty()) "" else "*".repeat(apiKey.length),
                    onValueChange = { typed ->
                        if (!typed.all { it == '*' }) apiKey = typed.trim()
                    },
                    label = { Text("sk-... (blank = load from device)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            // The key is encrypted but no longer auth-gated, so
                            // there is nothing to unlock before reading it. The
                            // prompt is kept here so this harness still exercises
                            // the same DeviceAuth path the app lock uses, which is
                            // the part that used to crash on API 30+.
                            DeviceAuth.authenticate(
                                hostActivity ?: return@OutlinedButton,
                                "Unlock API key",
                                "Confirm to continue"
                            ) { ok ->
                                if (!ok) {
                                    connState = "unlock cancelled"
                                    return@authenticate
                                }
                                val k = runCatching { secureKeys.read() }.getOrNull()
                                apiKey = k.orEmpty()
                                connState = if (apiKey.isNotEmpty()) {
                                    "decrypted ${apiKey.length} chars " +
                                        "(strongbox=${secureKeys.isStrongBoxBacked()})"
                                } else "no key stored - paste one and Connect"
                            }
                        }
                    ) { Text("Load") }

                    Button(
                        onClick = {
                            val port = (state as? EngineState.Running)?.port ?: return@Button
                            connBusy = true
                            connState = "connecting..."
                            scope.launch {
                                connState = try {
                                    val api = CrushApi(port)
                                    val s = CrushSession(api, WorkspaceManager(api))
                                    session = s
                                    val d = ctx.filesDir.absolutePath
                                    val opened = s.openWorkspace("$d/workspace", "$d/crush/state")
if (apiKey.isNotBlank()) {
                                         val r = s.ensureAgentReady(apiKey, "opencode-zen", modelId = "probe-me")
                                         // Persist to the Keystore so the probe
                                         // and the real UI can reuse it without
                                         // a plaintext file.
                                         val store = com.opencode.chat.data.local
                                             .SecureKeyStore(ctx)
                                         runCatching { store.write(apiKey) }
                                             .onFailure { connState = "key save failed: ${it.message}" }
                                         (listOf("ws=${r.workspaceId.take(8)}",
                                              "key saved (strongbox=${store.isStrongBoxBacked()})") + r.notes)
                                             .joinToString("\n")
                                     } else {
                                        opened.notes.joinToString("\n") + "\n(no key: agent not initialised)"
                                    }
                                } catch (e: Exception) {
                                    "ERROR ${e.javaClass.simpleName}: ${e.message?.take(160)}"
                                }
                                connBusy = false
                            }
                        },
                        enabled = !connBusy && (state as? EngineState.Running)?.port != null
                    ) { Text(if (connBusy) "Connecting..." else "Connect") }
                }
                Text(connState, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }

        var smoke by remember { mutableStateOf("") }
        var smokeBusy by remember { mutableStateOf(false) }
        OutlinedButton(
            onClick = {
                val port = (state as? EngineState.Running)?.port ?: return@OutlinedButton
                smokeBusy = true
                scope.launch {
                    smoke = try {
                        CrushSmokeTest(port).run()
                    } catch (e: Exception) {
                        "FATAL ${e.javaClass.simpleName}: ${e.message}"
                    }
                    smokeBusy = false
                    Log.i("CrushSmoke", "RESULT>>>\n$smoke\n<<<END")
                }
            },
            enabled = !smokeBusy && (state as? EngineState.Running)?.port != null
        ) { Text(if (smokeBusy) "Testing..." else "Test Crush API") }

        if (smoke.isNotBlank()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("API smoke test", style = MaterialTheme.typography.labelMedium)
                    Text(smoke, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }

        // Single self-driving test. One tap performs the whole flow, so the
        // engine cannot restart between intermediate taps and leave a stale
        // workspace id behind. The port is read through a provider for the
        // same reason.
        var e2e by remember { mutableStateOf("not run") }
        var e2eBusy by remember { mutableStateOf(false) }

        Button(
            onClick = {
                val port = (state as? EngineState.Running)?.port ?: return@Button
                e2eBusy = true
                e2e = "running..."
                scope.launch {
                    // Tell the supervisor a long request is in flight. Without
                    // this, setWorkActive is dead code and the 60s hang grace can
                    // fire against a HEALTHY engine that is merely busy serving a
                    // prompt - which chaos testing observed happening.
                    supervisor.setWorkActive(true)
                    val result = try {
                        FullE2ETest(ctx, { (state as? EngineState.Running)?.port }, { apiKey }).run()
                    } catch (e: Exception) {
                        "ERROR ${e.javaClass.simpleName}: ${e.message}"
                    }
                    e2e = result
                    e2eBusy = false
                    supervisor.setWorkActive(false)
                    FullE2ETest.log(result)
                }
            },
            enabled = !e2eBusy && (state as? EngineState.Running)?.port != null
        ) { Text(if (e2eBusy) "Running end-to-end..." else "Run end-to-end test") }

        if (e2e.isNotBlank()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("End-to-end result", style = MaterialTheme.typography.labelMedium)
                    Text(e2e, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val verdict = when (state) {
                    is EngineState.Running -> "RESULT: PASS"
                    is EngineState.Failed -> "RESULT: FAIL"
                    else -> "RESULT: PENDING"
                }
                Text(
                    verdict,
                    style = MaterialTheme.typography.titleMedium,
                    color = when (state) {
                        is EngineState.Running -> MaterialTheme.colorScheme.primary
                        is EngineState.Failed -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Text(render(state), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}


private fun render(state: EngineState): String = when (state) {
    is EngineState.Stopped -> "Stopped. Tap Start to boot the engine."
    is EngineState.Starting -> "Booting (attempt ${state.attempt})..."
    is EngineState.Running -> buildString {
        appendLine("PASS - engine is live.")
        appendLine("pid       = ${state.pid}")
        appendLine("port      = ${state.port}")
        appendLine("boot time = ${state.bootMs} ms")
        appendLine()
        appendLine("/v1/version ->")
        appendLine(state.version)
    }
    is EngineState.Failed -> buildString {
        appendLine("FAIL - ${state.reason}")
        state.detail?.takeIf { it.isNotBlank() }?.let {
            appendLine()
            appendLine(it)
        }
    }
}

