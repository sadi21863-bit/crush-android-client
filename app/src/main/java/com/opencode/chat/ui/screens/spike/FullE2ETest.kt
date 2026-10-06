package com.opencode.chat.ui.screens.spike

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.opencode.chat.data.crush.CrushApi
import com.opencode.chat.data.crush.CrushSession
import com.opencode.chat.data.crush.CrushStreamProbe
import com.opencode.chat.data.crush.WorkspaceManager
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * One-shot end-to-end test: boot -> workspace -> key -> agent -> prompt -> reply.
 *
 * Deliberately a single action. Driving the spike through individual taps meant
 * every intermediate step could restart the engine between taps, leaving the
 * next step holding a stale workspace id, and it made results depend on which
 * button happened to be under a guessed coordinate.
 *
 * The port is passed in as a provider rather than a value, so a restart between
 * steps is picked up instead of silently failing.
 */
class FullE2ETest(
    private val context: Context,
    private val portProvider: () -> Int?,
    private val apiKeyProvider: () -> String
) {

    suspend fun run(): String {
        val out = StringBuilder()

        suspend fun step(name: String, block: suspend () -> String) {
            out.appendLine("--- $name")
            out.appendLine(
                try {
                    withTimeoutOrNull(150_000) { block() } ?: "TIMEOUT (150s)"
                } catch (e: Exception) {
                    "ERROR ${e.javaClass.simpleName}: ${e.message?.take(200)}"
                }
            )
            out.appendLine()
        }

        // 0. key comes from the caller, which reads it out of SecureKeyStore.
        // The old world-readable /sdcard zenkey.txt fallback is deliberately
        // gone: that file was readable by any app holding storage permission.
        val key = apiKeyProvider().trim()
        out.appendLine("key source: ${if (key.isNotBlank()) "${key.length} chars (keystore)" else "MISSING"}")
        out.appendLine()

        val api = CrushApi(portProvider() ?: return "NO ENGINE RUNNING")
        val mgr = WorkspaceManager(api)
        val session = CrushSession(api, mgr)
        val d = context.filesDir.absolutePath

        step("workspace + provider key + agent/init") {
            session.openWorkspace("$d/workspace", "$d/crush/state")
            val r = if (key.isNotBlank()) {
                session.ensureAgentReady(key, "opencode-zen", modelId = "probe-me")
            } else {
                return@step "no API key - cannot initialise the agent"
            }
            r.notes.joinToString("\n") + "\nREADY=${r.isReady}"
        }

        step("live prompt -> streamed reply") {
            // Re-read the port: the engine may have restarted since Connect.
            api.port = portProvider() ?: return@step "engine stopped"
            val r = CrushStreamProbe(api, Gson()).probe(mgr, "Reply with exactly: HELLO FROM CRUSH")
            buildString {
                appendLine("session=${r.sessionId.take(8)} frames=${r.frames}")
                appendLine("events=${r.messageTypes.groupingBy { it }.eachCount()}")
                appendLine("finished=${r.finished}")
                if (r.error != null) appendLine("error=${r.error}")
                if (r.thinking.isNotBlank()) appendLine("thinking=${r.thinking.take(150)}")
                if (r.toolCalls.isNotEmpty()) appendLine("tools=${r.toolCalls}")
                appendLine("--- reply ---")
                appendLine(r.finalText.ifBlank { "(no text)" })
            }
        }

        return out.toString()
    }

    companion object {
        fun log(result: String) = Log.i(TAG, "E2E>>>\n$result\n<<<E2E")
        private const val TAG = "CrushE2E"
    }
}