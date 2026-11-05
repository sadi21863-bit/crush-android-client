package com.crush.mobile.data.crush

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * §3 of the Gate-7 design memo (2026-11-05): the stale-VM connection
 * reclaim's pure rules, pinned in plain JUnit on JVM-built
 * [CrushConnection] shells (never started, so no reader thread exists -
 * the connections only serve as registry-keyed identities here).
 *
 * Rule rows of the memo's §4 test matrix that land here:
 * - attach supersedes and revokes the previous registration (legacy ""
 *   included);
 * - revoke ONLY if the owner epoch matches (revoke-only-if-own);
 * - revoke-deferred-while-run-in-flight as a pure timing rule;
 * - the reclaim predicate [ConnectionRegistry.isSuperseded].
 *
 * The stop is injected because the registry must never OWN connection
 * teardown anyway - it only asks - and a recorded stop keeps the test JVM
 * free of sockets, exactly like the registry needs no re-threading to
 * unwind the reader (CrushConnection's own stop is caller-safe).
 */
class ConnectionRegistryTest {

    private val stops = mutableListOf<StopRecord>()

    private data class StopRecord(val conn: CrushConnection, val reason: String)

    /** `stopped` answers through [stops] so every test asserts from one well. */
    private fun newRegistry(): ConnectionRegistry =
        ConnectionRegistry { conn, reason -> stops.add(StopRecord(conn, reason)) }

    private fun freshConnection(): CrushConnection = CrushConnection(
        port = 1,
        router = CrushEventRouter(),
        gson = Gson(),
        socketFile = File("/tmp/unused-reclaim.sock"),
        clientSource = { null }, // never resolves: no attach is performed in these tests
    )

    private fun stopped(conn: CrushConnection): Boolean = stops.any { it.conn === conn }
    private fun stopsOf(conn: CrushConnection): Int = stops.count { it.conn === conn }

    // ------------------------------------------------------------------
    // attach: one reader per key; supersede revokes the predecessor
    // ------------------------------------------------------------------

    @Test
    fun `attach supersedes the previous registration and stops its connection`() {
        val reg = newRegistry()
        val older = freshConnection()
        val newer = freshConnection()
        reg.attach("pA", older, 1) { false }
        reg.attach("pA", newer, 2) { false }

        assertTrue(
            "the superseded read: the older VM's connection must be stopped by the registry",
            stopped(older)
        )
        assertEquals(
            "exactly one stop, exactly once - no ladder resurrection",
            1,
            stopsOf(older)
        )
        assertFalse("the NEW registration's connection is untouched", stopped(newer))
        assertTrue(
            "the older observer's touch now sees supersession",
            reg.isSuperseded(1, older)
        )
        assertFalse("the newer observer stays live", reg.isSuperseded(2, newer))
        assertTrue("the supersede reason is named", stops.single().reason.startsWith("superseded by epoch "))
    }

    @Test
    fun `the legacy empty key is superseded the same way`() {
        val reg = newRegistry()
        val older = freshConnection()
        val newer = freshConnection()
        reg.attach("", older, 1) { false }
        reg.attach("", newer, 2) { false }
        assertTrue(
            "without supersession a round trip leaves two live readers on ONE live engine",
            stopped(older)
        )
        assertFalse(stopped(newer))
    }

    @Test
    fun `a VM replacing its own connection is not a supersession`() {
        val reg = newRegistry()
        val own = freshConnection()
        val rebuilt = freshConnection()
        reg.attach("pA", own, 7) { false }
        reg.attach("pA", rebuilt, 7) { false } // same VM: identity-change rebuild
        assertTrue(
            "the registry must not stop for the same VM's rebuild - the VM stopped its own " +
                "outgoing connection at the rebuild point",
            stops.isEmpty()
        )
        assertFalse("the rebuilt registration stays live for its owner", reg.isSuperseded(7, rebuilt))
        assertFalse(
            "the same VM's replaced conn is not classified as superseded either (same epoch)",
            reg.isSuperseded(7, own)
        )
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
        assertEquals("the entry is already gone; nothing stops twice", 1, stopsOf(conn))
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
        assertFalse("a foreign key's reader must survive a foreign revoke", stopped(b))
        assertFalse(
            "the surviving registration answers its owner as live",
            reg.isSuperseded(2, b)
        )
    }

    @Test
    fun `revokeIfOwn only ever stops the caller's own registration`() {
        val reg = newRegistry()
        val vmOne = freshConnection()
        reg.attach("pA", vmOne, 11) { false }

        assertFalse(
            "a matching-less epoch must not kill someone else's reader",
            reg.revokeIfOwn("pA", 999, "chat entry cleared")
        )
        assertEquals("epoch mismatch means zero stops", 0, stops.size)

        assertTrue(
            "the matching owner's cleanup revokes its own registration",
            reg.revokeIfOwn("pA", 11, "chat entry cleared")
        )
        assertTrue(stopped(vmOne))
        assertFalse(
            "after the removed registration with nothing newer, the reclaim predicate does not scoop",
            reg.isSuperseded(11, vmOne)
        )
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
        assertTrue(
            "a run in flight on the key defers (same skip the engine stop uses)",
            reg.revokeIsDeferred(true)
        )
        assertFalse(
            "no run in flight revokes NOW (same fire the engine stop uses)",
            reg.revokeIsDeferred(false)
        )
    }

    @Test
    fun `a deferred revocation pins the stop until the run's end flush runs it`() {
        val reg = newRegistry()
        val conn = freshConnection()
        reg.attach("pA", conn, 1) { false }

        reg.revoke("pA", "instance switched away") { true } // run in flight on pA
        assertTrue(
            "a turn's stream is never cut mid-run",
            stops.isEmpty()
        )
        assertFalse(
            "the deferred observer still reads its registration as its own",
            reg.isSuperseded(1, conn)
        )

        reg.flushDeferredRevocation("pA") // reportRunInFlight(pA, false)
        assertTrue("the run is over: the pinned stop runs", stopped(conn))
        assertFalse(
            "a NOT-scooped own revoke lets the same VM's rebind path proceed",
            reg.isSuperseded(1, conn)
        )
    }

    @Test
    fun `a deferred flush must not kill a newer VM's live registration`() {
        val reg = newRegistry()
        val oldConn = freshConnection()
        val newConn = freshConnection()
        // The OLD VM's reader gets superseded WHILE the run is in flight:
        // the supersede stop defers too.
        reg.attach("pA", oldConn, 1) { true }
        reg.attach("pA", newConn, 2) { true }
        assertTrue(stops.isEmpty())
        assertFalse("the newer VM owns the spot live", reg.isSuperseded(2, newConn))

        reg.flushDeferredRevocation("pA") // run over
        assertTrue("the pinned old-conn stop happened", stopped(oldConn))
        assertFalse(
            "the newer VM's own connection stays its live one",
            reg.isSuperseded(2, newConn)
        )
        assertEquals("only the deferred conn was stopped", 1, stops.size)
    }

    @Test
    fun `two deferred stops for one key both complete at the run's end`() {
        val reg = newRegistry()
        val superseded = freshConnection()
        val current = freshConnection()
        reg.attach("pA", superseded, 1) { false }
        // Run IN FLIGHT on the key: the supersede of the old reader defers.
        reg.attach("pA", current, 2) { true }
        // Same key flagged in flight, a switch lands: the current reader's
        // revoke defers too - two pinned stops, different origins.
        reg.revoke("pA", "instance switched away") { true }
        assertTrue("nothing cuts while the turn streams", stops.isEmpty())
        reg.flushDeferredRevocation("pA")
        assertTrue(
            "both pinned stops ran at the run's end; neither dropped (no reader leak)",
            stopped(superseded) && stopped(current)
        )
        assertEquals(2, stops.size)
    }

    // ------------------------------------------------------------------
    // isSuperseded: the reclaim predicate cases
    // ------------------------------------------------------------------

    @Test
    fun `the reclaim predicate passes a fresh VM's pre-registration touch through`() {
        val reg = newRegistry()
        val notRegistered = freshConnection()
        assertFalse("a conn the registry never saw cannot be superseded", reg.isSuperseded(5, notRegistered))
        assertFalse("a null connection (first touch, none built) passes", reg.isSuperseded(5, null))
    }

    @Test
    fun `a stale VM scooped by a newer one retires on its next touch`() {
        val reg = newRegistry()
        val stale = freshConnection()
        val live = freshConnection()
        reg.attach("pA", stale, 1) { false }
        reg.attach("pA", live, 2) { false }
        assertTrue("the stale VM stops and logs ONCE (VM-side), never retries", reg.isSuperseded(1, stale))
    }

    @Test
    fun `an unregistered-but-abandoned observer with no newer owner is NOT scooped`() {
        val reg = newRegistry()
        val conn = freshConnection()
        reg.attach("pA", conn, 1) { false }
        reg.revoke("pA", "instance switched away") { false } // container guard, run over
        assertFalse(
            "the surviving VM's re-bind under its adopted key registers fresh and proceeds",
            reg.isSuperseded(1, conn)
        )
    }

    @Test
    fun `identity stays by REFERENCE, not structural equality`() {
        val reg = newRegistry()
        val a = freshConnection()
        val lookalike = freshConnection()
        reg.attach("pA", a, 1) { false }
        assertFalse(
            "two connection objects are never equal - the reader identity is the point",
            reg.isSuperseded(1, lookalike)
        )
    }
}
