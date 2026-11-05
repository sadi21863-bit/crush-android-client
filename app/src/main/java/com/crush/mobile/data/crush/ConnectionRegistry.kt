package com.crush.mobile.data.crush

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * §3 of the Gate-7 design memo (`docs/design-runtime-registry-2026-11-05.md`,
 * 2026-11-05): ownership table for the long-lived workspace event
 * connections, keyed by instanceKey.
 *
 * THE DEFECT IT RECLAIMS: a chat back-stack entry survives being covered
 * (`launchSingleTop` returns create a NEW chat entry above the old one), and
 * the stale `ChatViewModel` keeps its [CrushConnection] - its reader thread
 * rides the backed-off reconnect ladder (250ms -> 10s cap) against a socket
 * whose engine may have been stopped, until `onCleared`. Until now there was
 * no owner-of-record to say "this connection was replaced; stop observing".
 * This registry is that owner, modeled on WorkspaceManager's flush-by-read
 * pattern (P1-11).
 *
 * Invariants the chat layer relies on (each pinned by ConnectionRegistryTest):
 * - ONE reader per bound key: registering a NEW connection for a key
 *   supersedes (stops) the previous registration for that key, legacy ""
 *   included - without supersession a round trip leaves two live readers fed
 *   by the same live engine, the measured family of the reused-socket
 *   false-banner defect (2026-11-05).
 * - revoke-only-if-own: an old entry's `onCleared` must never kill a NEWER
 *   VM's connection for the same key.
 * - a turn in flight is never cut: revocation defers while the key has a run
 *   in flight and completes off the container's `reportRunInFlight(key,
 *   false)` - the same guard the engine-stop uses, byte-for-byte, so the two
 *   always agree about an instance being abandoned.
 *
 * The registry NEVER CONSTRUCTS connections - construction stays VM-owned -
 * and never picks transport. It only stores references and asks
 * [CrushConnection.stop] (already main-thread-safe: cancels the in-flight
 * call) through [stopConn], which is injectable only so the pure-JVM tests
 * can record stops without sockets.
 *
 * TOLERANCE, stated on purpose (P1-15 style): production operations all run
 * on the main thread (attach from the chat VM, revoke from the container's
 * binding setter, flush from `reportRunInFlight`), so [lock] protects an
 * interleaving that cannot exist today, plus same-thread tests. `entries` /
 * `registrationKey` are concurrent maps so the reclaim read
 * ([isSuperseded]) needs no lock at all - a stale-VM touch racing an attach
 * may at worst observe one beat old truth, which is exactly as honest as
 * the cached state a fresh subscription serves.
 */
class ConnectionRegistry(
    /** Stops a connection; production passes [CrushConnection.stop]. */
    private val stopConn: (CrushConnection, String) -> Unit = { conn, _ -> conn.stop() },
) {

    private class Entry(val conn: CrushConnection, val ownerEpoch: Long)

    private class Pending(val conn: CrushConnection, val ownerEpoch: Long, val reason: String)

    /** key -> the LIVE registration (at most one per key). Legacy "" included. */
    private val entries = ConcurrentHashMap<String, Entry>()

    /**
     * conn -> the key it was registered under, NEVER removed. This is the
     * tombstone memory the reclaim predicate needs: a revoked or superseded
     * connection is no longer in [entries], but "what key did this VM use to
     * observe" is exactly what decides whether a newer VM has since taken
     * that key over. Scope is one chat connection per attach per process.
     */
    private val registrationKey = ConcurrentHashMap<CrushConnection, String>()

    /**
     * key -> deferred stops. Only touched under [lock] (attach, revoke,
     * flush all hold it), so a plain map is enough. Multiple pendings per key
     * are legal: a superseded-while-in-flight stop and a revoked-while-
     * in-flight stop for the same key must BOTH run at the run's end, and
     * dropping one to keep the map single-slot would leak a reader for the
     * rest of the process.
     */
    private val pending = HashMap<String, MutableList<Pending>>()

    /** Serializes the check-then-act halves (attach supersede, revoke-if-own, flush). */
    private val lock = Any()

    private val epochs = AtomicLong()

    /**
     * Hands out the next process-unique observer epoch.
     *
     * The chat VM takes ONE at construction and registers every connection it
     * builds with it. Identity is whole point (the same generation-token
     * idiom the file uses for sessionViewEpoch/modelSelection): epochs must
     * be unique ACROSS VMs, so a superseding registration can never be
     * confused with the superseded one - which is why the registry hands
     * them out instead of each VM counting from zero.
     */
    fun nextOwnerEpoch(): Long = epochs.incrementAndGet()

    /**
     * Registers [conn] as THE live connection for [key]; revokes the previous
     * registration when it belonged to a DIFFERENT VM (a superseded observer
     * must not keep reading), deferring that stop while a run is in flight on
     * the key - the same rule the engine-stop guard applies.
     *
     * The re-registration of the SAME VM's rebuild is not a supersession: the
     * VM itself stopped its outgoing connection at the rebuild point
     * (attachEventStream), so the registry replaces the entry without stopping
     * anything.
     */
    fun attach(
        key: String,
        conn: CrushConnection,
        ownerEpoch: Long,
        runInFlightOnKey: () -> Boolean = { false },
    ) {
        synchronized(lock) {
            registrationKey[conn] = key
            val previous = entries[key]
            entries[key] = Entry(conn, ownerEpoch)
            if (previous != null && previous.conn !== conn && previous.ownerEpoch != ownerEpoch) {
                // The previous registration is already OFF the map (replaced
                // above); only its connection teardown is deferred-or-now.
                if (revokeIsDeferred(runInFlightOnKey())) {
                    pendingList(key).add(
                        Pending(previous.conn, previous.ownerEpoch, "superseded by epoch $ownerEpoch")
                    )
                } else {
                    stopConn(previous.conn, "superseded by epoch $ownerEpoch")
                }
            }
        }
    }

    /**
     * Revokes the key's CURRENT live connection now, or - while a run is in
     * flight on [key] - pins the stop and runs it off
     * [flushDeferredRevocation]. A key with no registration (legacy "" when
     * no VM assembled it, or a mid-rebuild window) revokes nothing: the
     * VM always stops its own connection object directly too.
     */
    fun revoke(key: String, reason: String, runInFlightOnKey: () -> Boolean = { false }) {
        synchronized(lock) {
            val e = entries[key] ?: return
            if (revokeIsDeferred(runInFlightOnKey())) {
                pendingList(key).add(Pending(e.conn, e.ownerEpoch, reason))
                return
            }
            entries.remove(key, e)
            stopConn(e.conn, reason)
        }
    }

    /**
     * onCleared's seam: revoke ONLY if the registry still maps [key] to THIS
     * VM's epoch - a newer VM's connection for the same key must not be
     * killed by an old entry's cleanup (the memo's revoke-only-if-own).
     */
    fun revokeIfOwn(key: String, ownerEpoch: Long, reason: String): Boolean {
        synchronized(lock) {
            val e = entries[key]
            if (e == null || e.ownerEpoch != ownerEpoch) return false
            entries.remove(key, e)
            stopConn(e.conn, reason)
            return true
        }
    }

    /**
     * Runs every stop deferred earlier for [key] - called off the container's
     * `reportRunInFlight(key, false)`, so a turn's stream is never cut
     * mid-run. Stops only the DEPENDS-ON-THE-RUN connections it pinned: a
     * newer VM's live registration for the key is left alone (its epoch owns
     * the spot by then).
     */
    fun flushDeferredRevocation(key: String) {
        synchronized(lock) {
            val batch = pending.remove(key) ?: return
            for (p in batch) {
                stopConn(p.conn, p.reason)
                val current = entries[key]
                if (current != null && current.conn === p.conn) entries.remove(key, current)
            }
        }
    }

    /**
     * The reclaim predicate (the memo's `isSuperseded`), consulted by the
     * STALE VM at the reader-retry seam before it re-enters the reconnect
     * ladder:
     *
     * - never-registered connection -> not superseded (the pre-register
     *   first touch of a fresh VM must pass through);
     * - still THE live registration with my epoch -> not superseded;
     * - registered under someone else's epoch -> superseded;
     * - seen-but-no-longer-registered (revoked / superseded / deferred) ->
     *   superseded ONLY when a DIFFERENT-epoch VM now holds the live spot on
     *   my key - that is the round-trip case where a newer VM took the
     *   surface. My OWN revoke with nothing newer (a plug-delete through the
     *   container's guard) is NOT a supersession: the surviving VM's
     *   re-bind under its adopted key registers fresh and proceeds.
     *
     * Both the map reads are lock-free, so the stale-VM touch stays cheap
     * (volatile + reference compare, as the memo demands).
     */
    fun isSuperseded(ownerEpoch: Long, conn: CrushConnection?): Boolean {
        if (conn == null) return false
        for ((_, e) in entries) {
            if (e.conn === conn) return e.ownerEpoch != ownerEpoch
        }
        val key = registrationKey[conn] ?: return false
        val current = entries[key] ?: return false
        return current.ownerEpoch != ownerEpoch
    }

    /**
     * PURE: the deferral rule. Mirrors the engine-stop guard byte-for-byte,
     * so "engine stops on switch" and "connection revokes on switch" cannot
     * disagree about an in-flight turn (the memo's §3: the revocation runs
     * off `reportRunInFlight(key, false)`).
     */
    internal fun revokeIsDeferred(runInFlightOnKey: Boolean): Boolean = runInFlightOnKey

    /** Only called under [lock]. */
    private fun pendingList(key: String): MutableList<Pending> =
        pending.getOrPut(key) { mutableListOf() }
}
