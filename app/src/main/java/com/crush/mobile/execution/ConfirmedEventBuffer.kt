package com.crush.mobile.execution

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * V2-16 A2 second pass: bounded deferral for CONFIRMED events that arrive
 * before their POST acknowledgement is durable.
 *
 * A zero-row guarded write means "not yet applicable", not "applied". Events
 * are retained per (instance key, run id), replayed after ACKED commits, and
 * NEVER treated as evidence of completion on their own. Overflow is explicit:
 * the caller must record an INCOMPLETE/UNKNOWN outcome, never a complete one.
 */
class ConfirmedEventBuffer(
    private val maxEvents: Int = 16,
    private val maxBytes: Int = 32 * 1024,
) {
    data class Key(val instanceKey: String, val runId: String)

    private data class Entry(val event: ExecutionEvent, val bytes: Int)

    private val buffered = ConcurrentHashMap<Key, MutableList<Entry>>()
    private val incomplete = ConcurrentHashMap.newKeySet<Key>()

    /** Returns false on overflow; the caller must then record INCOMPLETE. */
    fun add(instanceKey: String, runId: String, event: ExecutionEvent): Boolean {
        val key = Key(instanceKey, runId)
        if (incomplete.contains(key)) return false
        val list = buffered.computeIfAbsent(key) { Collections.synchronizedList(mutableListOf()) }
        synchronized(list) {
            val size = (event.detail?.length ?: 0) + 64
            val existingBytes = list.sumOf { it.bytes }
            if (list.size >= maxEvents || existingBytes + size > maxBytes) {
                incomplete.add(key)
                list.clear()
                return false
            }
            // A repeated frame is one fact, not additional evidence.
            if (list.none { it.event.type == event.type && it.event.detail == event.detail }) {
                list += Entry(event, size)
            }
        }
        return true
    }

    /** Removes and returns buffered events; order of arrival is preserved. */
    fun drain(instanceKey: String, runId: String): List<ExecutionEvent> {
        val key = Key(instanceKey, runId)
        val list = buffered.remove(key) ?: return emptyList()
        return synchronized(list) { list.map { it.event } }
    }

    fun isIncomplete(instanceKey: String, runId: String): Boolean =
        incomplete.contains(Key(instanceKey, runId))

    fun clear(instanceKey: String, runId: String) {
        val key = Key(instanceKey, runId)
        buffered.remove(key)
        incomplete.remove(key)
    }

    /** Bounded view for diagnostics; no body or prompt text is exposed. */
    fun pendingCount(instanceKey: String, runId: String): Int {
        val list = buffered[Key(instanceKey, runId)] ?: return 0
        return synchronized(list) { list.size }
    }
}
