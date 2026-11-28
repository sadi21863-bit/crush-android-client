package com.crush.mobile.data.crush

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CyclicBarrier

/**
 * §3 of the Gate-7 design memo (2026-11-05): the stale-VM connection reclaim's
 * pure rules, pinned in plain JUnit on JVM-built [CrushConnection] shells
 * (never started, so no reader thread exists).
 *
 * L3 (2026-11-24): supersession is answered by `(key, ownerEpoch)` and the live
 * `entries` map. The old connection->key tombstone map is gone, so these tests
 * pass values only; no test depends on retaining the connection object.
 */
class ConnectionRegistryTest {

    private val stops = mutableListOf<StopRecord>()

    private data class StopRecord(val conn: CrushConnection, val reason: String)

    private fun newRegistry(): ConnectionRegistry =
        ConnectionRegistry { conn, reason -> stops.add(StopRecord(conn, reason)) }

    private fun freshConnection(): CrushConnection = CrushConnection(
        port = 1,
        router = CrushEventRouter(),
        gson = Gson(),
        socketFile = File("/tmp/unused-reclaim.sock"),
        clientSource = { null },
    )

    private fun stopped(conn: CrushConnection): Boolean = stops.any { it.conn === conn }
    private fun stopsOf(conn: CrushConnection): Int = stops.count { it.conn === conn }

    // ------------------------------------------------------------------
    // attach: one reader per key; supersede revokes the predecessor
    // ------------------------------------------------------------------

    @Test
    fun `an older epoch attach cannot evict the newer live reader`() {
        val reg = newRegistry()
        val newer = freshConnection()
        val lateOlder = freshConnection()
        reg.attach("pA", newer, ownerEpoch = 2)
        reg.attach("pA", lateOlder, ownerEpoch = 1)

        assertTrue("late conn must be stopped", stopped(lateOlder))
        assertFalse("newer conn must survive", stopped(newer))
        assertTrue("older owner must be superseded", reg.isSuperseded("pA", 1))
        assertFalse("newer owner must remain current", reg.isSuperseded("pA", 2))
    }

    @Test
    fun `attach supersedes the previous registration and stops its connection`() {
        val reg = newRegistry()
        val older = freshConnection()
        val newer = freshConnection()
        reg.attach("pA", older, 1) { false }
        reg.attach("pA", newer, 2) { false }

        assertTrue("the superseded connection is stopped", stopped(older))
        assertEquals("exactly one stop", 1, stopsOf(older))
        assertFalse("the new registration is untouched", stopped(newer))
        assertTrue("epoch 1 is superseded by the live epoch 2", reg.isSuperseded("pA", 1))
        assertFalse("epoch 2 owns the key", reg.isSuperseded("pA", 2))
        assertTrue("the reason is named", stops.single().reason.startsWith("superseded by epoch "))
    }

    @Test
    fun `the legacy empty key is superseded the same way`() {
        val reg = newRegistry()
        val older = freshConnection()
        val newer = freshConnection()
        reg.attach("", older, 1) { false }
        reg.attach("", newer, 2) { false }
        assertTrue("the legacy key is not special", stopped(older))
        assertFalse("the live legacy reader survives", stopped(newer))
        assertTrue("epoch 1 is superseded on the legacy key", reg.isSuperseded("", 1))
    }

    @Test
    fun `a VM replacing its own connection is not a supersession`() {
        val reg = newRegistry()
        val own = freshConnection()
        reg.attach("pA", own, 7) { false }
        reg.attach("pA", freshConnection(), 7) { false }
        assertTrue("same-epoch rebuild is the VM's own teardown", stops.isEmpty())
        assertFalse("the same epoch stays current", reg.isSuperseded("pA", 7))
    }

    // ------------------------------------------------------------------
    // revoke: now, and revoke-only-if-own
    // ------------------------------------------------------------------

    @Test
    fun `revoke stops the current registration and a second revoke is a no-op`() {
        val reg = newRegistry()
        val conn = freshConnection()
        reg.attach("pA", conn, 1) { false }
        reg.revoke("pA", "instance switched away") { false }
        assertTrue(stopped(conn))
        reg.revoke("pA", "instance switched away") { false }
        assertEquals("nothing stops twice", 1, stopsOf(conn))
        assertFalse("no newer owner, no supersession", reg.isSuperseded("pA", 1))
    }

    @Test
    fun `revoke only acts on the key it was asked about`() {
        val reg = newRegistry()
        val a = freshConnection()
        val b = freshConnection()
        reg.attach("pA", a, 1) { false }
        reg.attach("pB", b, 2) { false }
        reg.revoke("pA", "instance switched away") { false }
        assertTrue(stopped(a))
        assertFalse("a foreign key's reader survives", stopped(b))
        assertFalse("the surviving registration answers its owner as live", reg.isSuperseded("pB", 2))
    }

    @Test
    fun `revokeIfOwn only ever stops the caller's own registration`() {
        val reg = newRegistry()
        val vmOne = freshConnection()
        reg.attach("pA", vmOne, 11) { false }

        assertFalse("a mismatched epoch must not kill someone else's reader", reg.revokeIfOwn("pA", 999, "chat entry cleared"))
        assertEquals("epoch mismatch means zero stops", 0, stops.size)

        assertTrue("the matching owner's cleanup revokes its registration", reg.revokeIfOwn("pA", 11, "chat entry cleared"))
        assertTrue(stopped(vmOne))
        assertFalse("removed with nothing newer, no scoop", reg.isSuperseded("pA", 11))
    }

    @Test
    fun `revokeIfOwn for an unregistered key is a no-op`() {
        val reg = newRegistry()
        assertFalse(reg.revokeIfOwn("pA", 11, "chat entry cleared"))
        assertTrue(stops.isEmpty())
    }

    // ------------------------------------------------------------------
    // revoke-deferred-while-run-in-flight: pure rule + flush behaviour
    // ------------------------------------------------------------------

    @Test
    fun `the deferral rule mirrors the engine-stop guard byte-for-byte`() {
        val reg = newRegistry()
        assertTrue("a run in flight defers", reg.revokeIsDeferred(true))
        assertFalse("no run in flight revokes now", reg.revokeIsDeferred(false))
    }

    @Test
    fun `a deferred revocation pins the stop until the run's end flush runs it`() {
        val reg = newRegistry()
        val conn = freshConnection()
        reg.attach("pA", conn, 1) { false }
        reg.revoke("pA", "instance switched away") { true }
        assertTrue("a turn's stream is never cut mid-run", stops.isEmpty())
        assertFalse("the deferred observer still answers as its own", reg.isSuperseded("pA", 1))
        reg.flushDeferredRevocation("pA")
        assertTrue("the pinned stop runs at the run's end", stopped(conn))
        assertFalse("own revoke with nothing newer", reg.isSuperseded("pA", 1))
    }

    @Test
    fun `a deferred flush must not kill a newer VM's live registration`() {
        val reg = newRegistry()
        val oldConn = freshConnection()
        val newConn = freshConnection()
        reg.attach("pA", oldConn, 1) { true }
        reg.attach("pA", newConn, 2) { true }
        assertTrue("both stops are pinned", stops.isEmpty())
        assertTrue("the old epoch is superseded while B is live", reg.isSuperseded("pA", 1))
        assertFalse("the newer VM owns the spot live", reg.isSuperseded("pA", 2))
        reg.flushDeferredRevocation("pA")
        assertTrue("the pinned old stop happened", stopped(oldConn))
        assertFalse("the newer connection stays live", reg.isSuperseded("pA", 2))
        assertEquals("only the deferred conn was stopped", 1, stops.size)
    }

    @Test
    fun `two deferred stops for one key both complete at the run's end`() {
        val reg = newRegistry()
        val superseded = freshConnection()
        val current = freshConnection()
        reg.attach("pA", superseded, 1) { false }
        reg.attach("pA", current, 2) { true }
        reg.revoke("pA", "instance switched away") { true }
        assertTrue("nothing cuts while the turn streams", stops.isEmpty())
        reg.flushDeferredRevocation("pA")
        assertTrue("both pinned stops ran", stopped(superseded) && stopped(current))
        assertEquals(2, stops.size)
    }

    // ------------------------------------------------------------------
    // isSuperseded: the reclaim predicate cases (key + ownerEpoch)
    // ------------------------------------------------------------------

    @Test
    fun `an unknown key is not superseded`() {
        val reg = newRegistry()
        assertFalse("never registered", reg.isSuperseded("p-unknown", 5))
        assertFalse("unknown legacy key", reg.isSuperseded("", 5))
    }

    @Test
    fun `a stale epoch scooped by a newer one retires on its next touch`() {
        val reg = newRegistry()
        val stale = freshConnection()
        val live = freshConnection()
        reg.attach("pA", stale, 1) { false }
        reg.attach("pA", live, 2) { false }
        assertTrue("the stale epoch retires", reg.isSuperseded("pA", 1))
    }

    @Test
    fun `an abandoned observer with no newer owner is NOT scooped`() {
        val reg = newRegistry()
        reg.attach("pA", freshConnection(), 1) { false }
        reg.revoke("pA", "instance switched away") { false }
        assertFalse("own revoke with nothing newer is not supersession", reg.isSuperseded("pA", 1))
    }

    @Test
    fun `identity is key plus epoch, not connection object equality`() {
        val reg = newRegistry()
        reg.attach("pA", freshConnection(), 1) { false }
        assertFalse("a lookalike connection object is irrelevant", reg.isSuperseded("pA", 1))
        assertFalse("unknown key", reg.isSuperseded("pB", 1))
    }

    // ------------------------------------------------------------------
    // L3 adversarial scenarios (deterministic; no GC timing)
    // ------------------------------------------------------------------

    @Test
    fun `an old callback after replacement sees supersession with values only`() {
        val reg = newRegistry()
        reg.attach("pA", freshConnection(), 1) { false }
        reg.attach("pA", freshConnection(), 2) { false }
        // The old callback carries the pair captured at attach; no connection
        // object and no registry history are required.
        assertTrue(reg.isSuperseded("pA", 1))
        assertFalse(reg.isSuperseded("pA", 2))
    }

    @Test
    fun `a newer owner revoked before the old callback is not permanent supersession`() {
        val reg = newRegistry()
        reg.attach("pA", freshConnection(), 1) { false }
        reg.attach("pA", freshConnection(), 2) { false }
        reg.revoke("pA", "newer owner cleared") { false }
        assertFalse("no live owner: the old epoch is not scooped", reg.isSuperseded("pA", 1))
        assertFalse("the revoked newer epoch is not scooped either", reg.isSuperseded("pA", 2))
        assertEquals(0, reg.retainedRegistrationCountForTest())
    }

    @Test
    fun `same key same epoch rebuild is not superseded`() {
        val reg = newRegistry()
        reg.attach("pA", freshConnection(), 7) { false }
        reg.attach("pA", freshConnection(), 7) { false }
        assertFalse("same owner, same generation", reg.isSuperseded("pA", 7))
        assertTrue("same-epoch rebuild does not stop the predecessor", stops.isEmpty())
        assertEquals(1, reg.retainedRegistrationCountForTest())
    }

    @Test
    fun `concurrent attach and revoke leave at most one live owner`() {
        val reg = newRegistry()
        reg.attach("pA", freshConnection(), 1) { false }
        val gate = CyclicBarrier(2)
        val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val attachThread = Thread({
            runCatching {
                gate.await()
                reg.attach("pA", freshConnection(), 2) { false }
            }.onFailure { errors += it }
        }, "l3-attach")
        val revokeThread = Thread({
            runCatching {
                gate.await()
                reg.revoke("pA", "l3 concurrent revoke") { false }
            }.onFailure { errors += it }
        }, "l3-revoke")
        attachThread.start()
        revokeThread.start()
        attachThread.join(5_000)
        revokeThread.join(5_000)

        assertTrue("no concurrent operation threw: $errors", errors.isEmpty())
        assertFalse("both threads finished", attachThread.isAlive || revokeThread.isAlive)
        val live = reg.retainedRegistrationCountForTest()
        assertTrue("at most one live registration, was $live", live <= 1)
        if (live == 1) {
            assertTrue("if B survived, epoch 1 is superseded", reg.isSuperseded("pA", 1))
        } else {
            assertFalse("empty key: epoch 1 is not scooped", reg.isSuperseded("pA", 1))
        }
    }

    @Test
    fun `rebinding while a run is in flight keeps the old epoch superseded until flush`() {
        val reg = newRegistry()
        val old = freshConnection()
        reg.attach("pA", old, 1) { true }
        reg.attach("pA", freshConnection(), 2) { true }
        assertTrue("new owner is live", reg.isSuperseded("pA", 1))
        assertFalse("new owner answers itself", reg.isSuperseded("pA", 2))
        assertTrue("old stop is pinned", stops.isEmpty())
        reg.flushDeferredRevocation("pA")
        assertTrue("old stop ran", stopped(old))
        assertFalse("new still live", reg.isSuperseded("pA", 2))
    }

    @Test
    fun `revoking while the old router is alive does not scoop`() {
        val reg = newRegistry()
        val conn = freshConnection()
        reg.attach("pA", conn, 1) { false }
        reg.revoke("pA", "l3 revoke during router processing") { false }
        assertTrue("the connection was asked to stop", stopped(conn))
        assertFalse("no live owner, no supersession", reg.isSuperseded("pA", 1))
    }

    @Test
    fun `no per-connection history remains after normal revoke`() {
        val reg = newRegistry()
        repeat(5) {
            val conn = freshConnection()
            reg.attach("pA", conn, it.toLong() + 1) { false }
            reg.revoke("pA", "l3 cleanup") { false }
            assertEquals("no entry after revoke", 0, reg.retainedRegistrationCountForTest())
        }
        // The old design would have retained five full connections in its
        // registrationKey map; the exposed count and the deleted map pin that.
        assertEquals(0, reg.retainedRegistrationCountForTest())
    }

    @Test
    fun `a stale callback after revokeIfOwn returns false`() {
        val reg = newRegistry()
        reg.attach("pA", freshConnection(), 1) { false }
        assertTrue(reg.revokeIfOwn("pA", 1, "chat entry cleared"))
        assertFalse("cleared VM's delayed callback is not scooped", reg.isSuperseded("pA", 1))
        assertEquals(0, reg.retainedRegistrationCountForTest())
    }

    @Test
    fun `a delayed callback with no connection reference still resolves supersession`() {
        val reg = newRegistry()
        reg.attach("pA", freshConnection(), 1) { false }
        reg.attach("pA", freshConnection(), 2) { false }
        // Simulates the GC/weak-reference scenario with the recommended design:
        // the callback carries only the captured key and epoch, not the object.
        assertTrue("key + epoch is sufficient after the old object is gone", reg.isSuperseded("pA", 1))
    }

    @Test
    fun `two connection objects under one key are separated by owner epoch`() {
        val reg = newRegistry()
        reg.attach("pA", freshConnection(), 1) { false }
        reg.attach("pA", freshConnection(), 2) { false }
        assertTrue("epoch 1 is not the live owner", reg.isSuperseded("pA", 1))
        assertFalse("epoch 2 is the live owner", reg.isSuperseded("pA", 2))
        assertTrue("an epoch that never registered is not current", reg.isSuperseded("pA", 3))
    }
}
