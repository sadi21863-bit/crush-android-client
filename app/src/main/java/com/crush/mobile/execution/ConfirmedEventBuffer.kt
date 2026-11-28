package com.crush.mobile.execution

/**
 * V2-16 A2: bounded deferral for CONFIRMED events that arrive before their
 * POST acknowledgement is durable.
 *
 * All map membership and list lifecycle changes happen under ONE per-buffer
 * monitor. A per-list lock alone allowed add() to append to a list that clear()
 * had already detached, or to create a fresh list after a tombstone. A zero-row
 * guarded write means "not yet applicable", never "applied"; overflow is an
 * explicit INCOMPLETE condition.
 */
class ConfirmedEventBuffer(
    private val maxEvents: Int = 16,
    private val maxBytes: Int = 32 * 1024,
) {
    data class Key(val instanceKey: String, val runId: String)

    private data class Entry(val event: ExecutionEvent, val bytes: Int)

    private val lifecycleLock = Any()
    private val buffered = HashMap<Key, MutableList<Entry>>()
    private val incomplete = HashSet<Key>()
    private val tombstoned = HashSet<Key>()

    /** false means rejected (overflow or explicit invalidation). */
    fun add(instanceKey: String, runId: String, event: ExecutionEvent): Boolean {
        val key = Key(instanceKey, runId)
        synchronized(lifecycleLock) {
            if (incomplete.contains(key) || tombstoned.contains(key)) return false
            val list = buffered.computeIfAbsent(key) { mutableListOf() }
            val size = (event.detail?.length ?: 0) + 64
            val existingBytes = list.sumOf { it.bytes }
            if (list.size >= maxEvents || existingBytes + size > maxBytes) {
                incomplete.add(key)
                list.clear()
                buffered.remove(key)
                return false
            }
            if (list.none { it.event.type == event.type && it.event.detail == event.detail }) {
                list += Entry(event, size)
            }
            return true
        }
    }

    /** Read-only view; evidence stays pending until durable application. */
    fun peek(instanceKey: String, runId: String): List<ExecutionEvent> =
        synchronized(lifecycleLock) {
            buffered[Key(instanceKey, runId)]?.map { it.event } ?: emptyList()
        }

    /** Head-only removal; a non-head event can never detach later evidence. */
    fun remove(instanceKey: String, runId: String, event: ExecutionEvent) {
        synchronized(lifecycleLock) {
            val key = Key(instanceKey, runId)
            val list = buffered[key] ?: return
            if (list.firstOrNull()?.event === event) list.removeAt(0)
            if (list.isEmpty()) buffered.remove(key)
        }
    }

    /** Legacy tests only; production replay uses [peek]/[remove]. */
    fun drain(instanceKey: String, runId: String): List<ExecutionEvent> =
        synchronized(lifecycleLock) {
            val events = buffered.remove(Key(instanceKey, runId))?.map { it.event }
                ?: emptyList()
            events
        }

    fun isIncomplete(instanceKey: String, runId: String): Boolean =
        synchronized(lifecycleLock) { incomplete.contains(Key(instanceKey, runId)) }

    /** Explicit invalidation: atomic with add/remove, and tombstoned. */
    fun clear(instanceKey: String, runId: String) {
        synchronized(lifecycleLock) {
            val key = Key(instanceKey, runId)
            buffered.remove(key)
            incomplete.remove(key)
            tombstoned.add(key)
        }
    }

    /** Keys with unapplied evidence, for startup/retry reconciliation. */
    fun pendingKeys(): List<Key> = synchronized(lifecycleLock) { buffered.keys.toList() }

    fun pendingCount(instanceKey: String, runId: String): Int =
        synchronized(lifecycleLock) { buffered[Key(instanceKey, runId)]?.size ?: 0 }
}
