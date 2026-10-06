package com.opencode.chat.data.crush

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.Request
import java.io.IOException

/**
 * Consumes `GET /v1/workspaces/{id}/events`.
 *
 * Wire quirks, all confirmed against the running engine:
 *  - Only `data:` lines are written. No `event:`, no `id:`, no heartbeat.
 *  - No SSE `id:` means no `Last-Event-ID`, so a dropped stream cannot be
 *    resumed — it must be reconnected and then resynchronised from
 *    `GET .../sessions/{sid}/messages`.
 *  - An idle workspace emits nothing at all, so the client has no read timeout.
 */
class CrushEventStream(private val api: CrushApi, private val gson: com.google.gson.Gson) {

    @Volatile
    private var cancelled = false

    /** Opens the stream, retrying with the same ladder Crush's own client uses. */
    fun events(workspaceId: String, clientId: String): Flow<CrushEvent> = callbackFlow {
        // Held so the collector can cancel the in-flight call. Without this the
        // blocking readUtf8Line() never observes cancellation, leaking a socket
        // (and a thread) on every reconnect.
        var call: okhttp3.Call? = null
        var attempt = 0

        val reader = Thread({
            // A plain Thread has no CoroutineScope, so `cancelled` is the loop
            // condition; it is set from awaitClose on collector cancellation.
            while (!cancelled) {
                val url = "http://127.0.0.1:${api.port}/v1/workspaces/$workspaceId/events?client_id=$clientId"
                val request = Request.Builder().url(url)
                    .header("Accept", "text/event-stream")
                    .get()
                    .build()

                try {
                    val c = api.streamClient.newCall(request)
                    call = c
                    c.execute().use { response ->
                        if (!response.isSuccessful) {
                            throw IOException("events: HTTP ${response.code}")
                        }
                        attempt = 0
                        val source = response.body?.source()
                            ?: throw IOException("events: empty body")

                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            if (!line.startsWith(DATA_PREFIX)) continue

                            val json = line.removePrefix(DATA_PREFIX).trim()
                            if (json.isEmpty() || json == "[DONE]") continue

                            runCatching { decode(json) }
                                .onSuccess { trySend(it) }
                                .onFailure { Log.d(TAG, "skip malformed frame: ${it.message}") }
                        }
                    }
                } catch (e: IOException) {
                    if (cancelled) return@Thread
                    Log.w(TAG, "events stream dropped (attempt $attempt): ${e.message}")
                } catch (e: Exception) {
                    if (cancelled) return@Thread
                    Log.w(TAG, "events stream error: ${e.message}")
                } finally {
                    call = null
                }

                if (cancelled) return@Thread

                // Crush's own client uses 250ms -> 10s exponential backoff.
                val backoff = (INITIAL_BACKOFF_MS shl attempt.coerceAtMost(4))
                    .coerceAtMost(MAX_BACKOFF_MS)
                attempt++

                // The sleep MUST be inside a try. awaitClose interrupts this
                // thread, and an InterruptedException thrown out of a Runnable
                // escapes to the default uncaught handler, which on Android is
                // KillApplicationHandler - it takes the whole process down.
                //
                // That is not a rare race: awaitClose fires on every normal end
                // of a turn (throw StopStream on RUN_COMPLETE), on stream
                // timeout, and when the ViewModel is cleared. Crush drops the
                // connection when the run finishes, so the thread is frequently
                // sitting in exactly this backoff window when the interrupt
                // arrives.
                try {
                    Thread.sleep(backoff)
                } catch (e: InterruptedException) {
                    // Expected on cancel. Restore the flag so any higher-level
                    // blocking code can observe it, then leave quietly.
                    Thread.currentThread().interrupt()
                    Log.d(TAG, "backoff interrupted; stopping reader")
                    return@Thread
                }
            }
        }, "crush-sse").apply { isDaemon = true }

        reader.start()

        awaitClose {
            cancelled = true
            runCatching { call?.cancel() }
            reader.interrupt()
        }
    }

    private fun decode(json: String): CrushEvent {
        val root = JsonParser.parseString(json).asJsonObject

        val payloadType = CrushEvent.PayloadType.from(root.optString("type").orEmpty())
        val envelope = root.getAsJsonObject("payload")
            ?: return CrushEvent(payloadType, CrushEvent.ChangeType.UNKNOWN, com.google.gson.JsonObject())

        val change = CrushEvent.ChangeType.from(envelope.optString("type").orEmpty())
        val body = envelope.getAsJsonObject("payload")
            ?: com.google.gson.JsonObject()

        return CrushEvent(payloadType, change, body)
    }

    private companion object {
        const val TAG = "CrushEvents"
        const val DATA_PREFIX = "data:"
        const val INITIAL_BACKOFF_MS = 250L
        const val MAX_BACKOFF_MS = 10_000L
    }
}