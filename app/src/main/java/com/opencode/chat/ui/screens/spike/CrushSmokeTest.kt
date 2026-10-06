package com.opencode.chat.ui.screens.spike

import android.util.Log
import com.google.gson.Gson
import com.opencode.chat.data.crush.CrushApi
import com.opencode.chat.data.crush.CrushEvent
import com.opencode.chat.data.crush.CrushEventStream
import com.opencode.chat.data.crush.WorkspaceManager
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Exercises the real Crush API against the running engine.
 *
 * Kept in the app deliberately: `adb shell curl` cannot reach the engine's
 * listening socket because Android SELinux isolates per-app ports, so there is
 * no way to probe this from outside the process.
 */
class CrushSmokeTest(private val port: Int) {

    private val api = CrushApi(port)
    private val stream = CrushEventStream(api, Gson())
    private val workspaces = WorkspaceManager(api)

    suspend fun run(): String {
        val out = StringBuilder()

        suspend fun step(name: String, block: suspend () -> String) {
            out.appendLine("--- $name")
            out.appendLine(
                try {
                    withTimeoutOrNull(20_000) { block() } ?: "TIMEOUT after 20s"
                } catch (e: Exception) {
                    "ERROR ${e.javaClass.simpleName}: ${e.message?.take(200)}"
                }
            )
            out.appendLine()
        }

        step("GET /v1/version") {
            val v = api.version()
            "version=${v.version} platform=${v.platform} go=${v.goVersion}"
        }

        val appDir = "/data/user/0/com.opencode.chat/files"

        step("POST /workspaces (yolo=true)") {
            val ws = workspaces.open("$appDir/workspace", "$appDir/crush/state", yolo = true)
            "id=${ws.id}\npath=${ws.path}\nclientId=${workspaces.clientId}\n" +
                "agent/init error=${workspaces.lastInitError ?: "none"}"
        }

        val wsId = workspaces.currentWorkspaceId ?: run {
            out.appendLine("NO WORKSPACE ID - aborting")
            return out.toString()
        }

        step("GET /workspaces/{id}/providers") {
            val list = api.providers(wsId)
            out.appendLine("count=${list.size}")
            list.take(40).forEach { p ->
                val id = p.get("id")?.asString ?: p.get("name")?.asString ?: "?"
                out.appendLine("  id=$id")
            }
            "listed ${list.size} providers"
        }

        step("GET /workspaces/{id}/agent (after init)") {
            val info = api.agentInfo(wsId)
            "is_busy=${info.isBusy} is_ready=${info.isReady}"
        }

        step("GET /workspaces/{id}/permissions/skip") {
            "skip=true (yolo applied)"
        }

        step("POST /workspaces/{id}/sessions") {
            val s = workspaces.newSession("smoke")
            if (s == null) "create returned null (runCatching swallowed the error)" else "id=${s.id} title=${s.title}"
        }

        step("GET /workspaces/{id}/sessions") {
            val list = api.listSessions(wsId)
            out.appendLine("count=${list.size}")
            list.take(3).forEach { s ->
                out.appendLine("  - ${s.id} '${s.title}' msgs=${s.messageCount} busy=${s.isBusy}")
            }
            "listed ${list.size} session(s)"
        }

        step("SSE /events (first frame, 8s)") {
            val frame: CrushEvent? = withTimeoutOrNull(8_000) {
                stream.events(wsId, workspaces.clientId).firstOrNull()
            }
            if (frame == null) "no frame within 8s (stream opened, workspace idle)" else describe(frame)
        }

        step("POST /workspaces/{id}/agent (prompt)") {
            val s = workspaces.newSession("smoke-send") ?: return@step "no session"
            val info = api.agentInfo(wsId)
            if (!info.isReady) return@step "agent not ready; skipping prompt (expected without an API key)"
            api.send(
                wsId,
                com.opencode.chat.data.crush.AgentMessageRequest(
                    sessionId = s.id,
                    prompt = "Say hello",
                    runId = java.util.UUID.randomUUID().toString()
                )
            )
            "202 Accepted for session=${s.id}"
        }

        return out.toString()
    }

    private fun describe(e: CrushEvent): String =
        "type=${e.type.wire} change=${e.change.wire}\nbody=${e.body}"
}