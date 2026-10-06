package com.opencode.chat.data.crush

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout

/**
 * End-to-end proof that a prompt reaches a live model and tokens stream back.
 *
 * Order matters and mirrors what the real chat UI must do:
 *   1. Open the SSE stream FIRST. The run is dispatched detached from the POST,
 *      so events can start arriving before a prompt is even sent; attaching late
 *      drops early deltas.
 *   2. Send the prompt with a fresh run_id.
 *   3. Consume message events until run_complete matches that run_id.
 *
 * Message events carry the WHOLE message, not a delta, so each update replaces
 * the previous text rather than appending to it.
 */
class CrushStreamProbe(private val api: CrushApi, private val gson: Gson = Gson()) {

    data class Result(
        val sessionId: String,
        val runId: String,
        val frames: Int,
        val messageTypes: List<String>,
        val finalText: String,
        val thinking: String,
        val toolCalls: List<String>,
        val finished: Boolean,
        val error: String?
    )

    suspend fun probe(
        workspaces: WorkspaceManager,
        prompt: String,
        timeoutMs: Long = 120_000
    ): Result {
        val wsId = workspaces.currentWorkspaceId
            ?: return Result("", "", 0, emptyList(), "", "", emptyList(), false, "no workspace attached")

        // The workspace id dies with the engine process. If the engine has been
        // restarted since Connect, re-attach before doing anything else.
        val alive = runCatching { api.version().version }.isSuccess
        if (!alive) {
            return Result("", "", 0, emptyList(), "", "", emptyList(), false,
                "engine is not reachable - tap Connect again")
        }

        val session = try {
            workspaces.newSession("stream-probe")
        } catch (e: Exception) {
            return Result("", "", 0, emptyList(), "", "", emptyList(), false,
                "session create failed: ${e.message?.take(140)}")
        }

        val runId = java.util.UUID.randomUUID().toString()

        // 1. subscribe first
        val events = streamOf(wsId, workspaces.clientId)

        // 2. send
        api.send(wsId, AgentMessageRequest(sessionId = session.id, prompt = prompt, runId = runId))

        // 3. consume
        var frames = 0
        val types = mutableListOf<String>()
        var text = ""
        var thinking = ""
        var tools = emptyList<String>()
        var finished = false
        var providerError: String? = null

        try {
            withTimeout(timeoutMs) {
                events.collect { ev ->
                    frames++
                    when (ev.type) {
                        CrushEvent.PayloadType.MESSAGE -> {
                            val msg = gson.fromJson(ev.body, StreamMessage::class.java)
                            // Only assistant text is the reply. The user message
                            // is republished too, and counting it reports the
                            // prompt back as if it were the answer.
                            if (msg.role == "assistant") {
                                // Replace, never append.
                                msg.text.takeIf { it.isNotBlank() }?.let { text = it }
                                msg.thinking.takeIf { it.isNotBlank() }?.let { thinking = it }
                                msg.finishError?.let { providerError = it }
                            }
                            if (msg.toolCalls.isNotEmpty()) {
                                tools = msg.toolCalls.mapNotNull { c ->
                                    c.optString("name")
                                }
                            }
                            if (msg.isFinished) finished = true
                            types += "message/${msg.role}"
                        }

                        CrushEvent.PayloadType.RUN_COMPLETE -> {
                            val rc = gson.fromJson(ev.body, RunComplete::class.java)
                            types += "run_complete"
                            if (rc.runId == runId || rc.runId.isEmpty()) {
                                Log.i(TAG, "run_complete for our turn: ${rc.messageId}")
                                throw StopIteration()
                            }
                        }

                        CrushEvent.PayloadType.AGENT_EVENT -> types += "agent_event"

                        CrushEvent.PayloadType.PERMISSION_REQUEST -> {
                            types += "permission_request"
                            // yolo is on, but auto-allow so a probe cannot hang.
                            runCatching {
                                api.grant(
                                    wsId,
                                    PermissionGrant(
                                        permission = gson.fromJson(
                                            ev.body, PermissionRequest::class.java
                                        ),
                                        action = PermissionGrant.ALLOW
                                    )
                                )
                            }
                        }

                        CrushEvent.PayloadType.QUESTION_REQUEST -> {
                            types += "question"
                            runCatching { api.cancelQuestions(wsId) }
                        }

                        else -> types += ev.type.wire.ifEmpty { "unknown" }
                    }
                }
            }
        } catch (_: StopIteration) {
            // expected: our run completed
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            return Result(
                session.id, runId, frames, types, text, thinking, tools, finished,
                "timed out after ${timeoutMs / 1000}s"
            )
        } catch (e: Exception) {
            return Result(session.id, runId, frames, types, text, thinking, tools, finished,
                "${e.javaClass.simpleName}: ${e.message?.take(120)}")
        }

        return Result(session.id, runId, frames, types, text, thinking, tools, finished, providerError)
    }

    private fun streamOf(wsId: String, clientId: String) =
        CrushEventStream(api, gson).events(wsId, clientId)

    private class StopIteration : RuntimeException(null, null, false, false)

    private companion object {
        const val TAG = "CrushStream"
    }
}