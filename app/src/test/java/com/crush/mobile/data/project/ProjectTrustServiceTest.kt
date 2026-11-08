package com.crush.mobile.data.project

import com.crush.mobile.project.ProjectRef
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The S-02 gate's verdict matrix, pure JVM: the service drives a fake
 * [ProjectTrustSource] (in-memory map - the same seam the production store
 * implements over the project row's columns) and real temp-dir files, so the
 * ENTIRE content-fingerprint contract is tested without Android machinery.
 *
 * Known SHA-256 vectors pin the hashing itself (a silent digest bug that
 * still "worked" would otherwise pass every self-comparison below).
 */
class ProjectTrustServiceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Test vectors: the exact digests the service must produce. */
    private object KnownSha {
        const val sha256OfHello =
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
        const val sha256OfWorld =
            "486ea46224d1bb4fb680f34f7c9ad96a8f24ec88be73ea8e5a6c65260e9cb8a7"
    }

    private class FakeTrustSource : ProjectTrustSource {
        val records = mutableMapOf<UUID, ProjectTrustRecord>()
        var writes = 0

        override suspend fun trustRecordOf(id: UUID): ProjectTrustRecord? = records[id]

        override suspend fun writeTrustRecord(id: UUID, fingerprint: String?, consentedAtMs: Long) {
            writes++
            records[id] = ProjectTrustRecord(fingerprint = fingerprint, consentedAtMs = consentedAtMs)
        }
    }

    private fun refFor(dir: File) = ProjectRef(UUID.randomUUID(), "Test", dir)

    private fun serviceWith(source: ProjectTrustSource) =
        ProjectTrustService(source, stampNow = { 1_000L })

    @Test
    fun `no record with a crushrc present is Required first-consent`() = runBlocking {
        val dir = tmp.newFolder("a")
        File(dir, "crushrc").writeText("hello")
        val source = FakeTrustSource()
        val verdict = serviceWith(source).gate(refFor(dir))
        assertTrue(verdict is TrustVerdict.Required)
        assertEquals(
            "crushrc present, no consent recorded",
            (verdict as TrustVerdict.Required).reason
        )
    }

    @Test
    fun `no record with no crushrc is Allow and the gate writes nothing`() = runBlocking {
        val dir = tmp.newFolder("b")
        val source = FakeTrustSource()
        val verdict = serviceWith(source).gate(refFor(dir))
        assertTrue(verdict is TrustVerdict.Allow)
        // THE gate-before-bind law, service side: a verdict NEVER writes a
        // trust record. Only recordConsent does - so a dismissed dialog
        // cannot leave a half-consent behind, and a Required verdict cannot
        // become a silent Allow later.
        assertEquals(0, source.writes)
        assertTrue(source.records.isEmpty())
    }

    @Test
    fun `recordConsent produces the exact known fingerprint for the bytes`() = runBlocking {
        val dir = tmp.newFolder("c")
        val file = File(dir, "crushrc")
        file.writeText("hello")
        val source = FakeTrustSource()
        val recorded = serviceWith(source).recordConsent(refFor(dir))
        assertEquals(KnownSha.sha256OfHello, recorded)
        assertEquals(KnownSha.sha256OfHello, source.records.values.single().fingerprint)
    }

    @Test
    fun `matching fingerprint consents to Allow`() = runBlocking {
        val dir = tmp.newFolder("d")
        File(dir, "crushrc").writeText("hello")
        val service = serviceWith(FakeTrustSource())
        val ref = refFor(dir)
        service.recordConsent(ref)
        val verdict = service.gate(ref)
        assertTrue(verdict is TrustVerdict.Allow)
        // The Allow now CARRIES the gate's own hash: the record-on-allow
        // step may persist exactly this state and nothing else.
        assertEquals(
            KnownSha.sha256OfHello,
            (verdict as TrustVerdict.Allow).observedFingerprint
        )
    }

    @Test
    fun `changed bytes after consent is Required fingerprint moved`() = runBlocking {
        val dir = tmp.newFolder("e")
        val file = File(dir, "crushrc")
        file.writeText("hello")
        val service = serviceWith(FakeTrustSource())
        val ref = refFor(dir)
        service.recordConsent(ref) // consents to sha256(hello) == KnownSha.sha256OfHello
        file.writeText("world")
        val verdict = service.gate(ref)
        assertTrue(verdict is TrustVerdict.Required)
        assertEquals(KnownSha.sha256OfWorld, service.currentFingerprint(ref))
        assertEquals(
            "crushrc changed since consent (fingerprint moved)",
            (verdict as TrustVerdict.Required).reason
        )
    }

    @Test
    fun `crushrc removed after consent is Required re-verify`() = runBlocking {
        val dir = tmp.newFolder("f")
        val file = File(dir, "crushrc")
        file.writeText("hello")
        val service = serviceWith(FakeTrustSource())
        val ref = refFor(dir)
        service.recordConsent(ref)
        assertTrue(file.delete())
        val verdict = service.gate(ref)
        assertTrue(verdict is TrustVerdict.Required)
        assertEquals(
            "crushrc removed since consent (re-verify folder state)",
            (verdict as TrustVerdict.Required).reason
        )
    }

    @Test
    fun `noConfig recorded then the file appears is Required first-consent`() = runBlocking {
        val dir = tmp.newFolder("g")
        val ref = refFor(dir)
        val source = FakeTrustSource()
        val service = serviceWith(source)
        // Opened while crushrc-less: the Allow-by-absence state must have
        // been RECORDED at open time (the callers' contract) - a planted
        // crushrc while the app is closed now re-gates.
        assertTrue(service.gate(ref) is TrustVerdict.Allow)
        service.recordConsent(ref)
        assertTrue("the recorded state must be the noConfig one",
            source.trustRecordOf(ref.id)!!.fingerprint == null)
        File(dir, "crushrc").writeText("hello")
        val verdict = service.gate(ref)
        assertTrue(verdict is TrustVerdict.Required)
        assertEquals(
            "crushrc present, no consent recorded",
            (verdict as TrustVerdict.Required).reason
        )
    }

    @Test
    fun `noConfig state stays Allow while the folder stays crushrc-less`() = runBlocking {
        val dir = tmp.newFolder("h")
        val ref = refFor(dir)
        val service = serviceWith(FakeTrustSource())
        service.recordConsent(ref) // folds the noConfig state in
        assertTrue(service.gate(ref) is TrustVerdict.Allow)
    }

    @Test
    fun `consent survives a folder where the file was removed and re-planted as changed bytes`() =
        runBlocking {
            // The full adversarial sequence an offline attack can produce:
            // consented -> removed -> different crushrc planted. Every state
            // on that walk is Required; nothing in the service "re-trusts".
            val dir = tmp.newFolder("i")
            val file = File(dir, "crushrc")
            file.writeText("hello")
            val ref = refFor(dir)
            val service = serviceWith(FakeTrustSource())
            service.recordConsent(ref)
            assertTrue(file.delete())
            assertTrue(service.gate(ref) is TrustVerdict.Required)
            file.writeText("world")
            assertTrue(service.gate(ref) is TrustVerdict.Required)
            assertEquals(KnownSha.sha256OfWorld, service.currentFingerprint(ref))
        }

    @Test
    fun `a fake binder never binds without a record and the gate never consents for free`() =
        runBlocking {
            // Gate-before-bind ordering, fake-caller form (plan PR-A §5): the
            // binder's law is "bind only after a verified record". A Required
            // verdict leaves NO record (nothing to bind against), so a
            // compliant binder is inert; only recordConsent creates the state
            // the binder accepts.
            val dir = tmp.newFolder("j")
            File(dir, "crushrc").writeText("hello")
            val source = FakeTrustSource()
            val service = serviceWith(source)
            val ref = refFor(dir)

            var bound = false
            val verdict = service.gate(ref)
            when (verdict) {
                is TrustVerdict.Allow -> {
                    service.recordConsent(ref)
                    bound = true
                }
                is TrustVerdict.Required -> Unit // dialog shown; user cancelled
            }
            assertTrue(verdict is TrustVerdict.Required)
            assertTrue("a gate-Required path must not bind", !bound)
            assertTrue("a gate-Required path must not record", source.writes == 0)

            // Consent now: record first, then the same binder law binds.
            service.recordConsent(ref)
            if (source.trustRecordOf(ref.id)!!.fingerprint == service.currentFingerprint(ref)) {
                bound = true
            }
            assertTrue("a recorded consent must allow the binder through", bound)
        }

    // ---------------------------------------------------------------------------
    // Finding A: the gate->record window. The Allow path records through
    // confirmAtGate(ref, Allow.observedFingerprint), which re-reads the REAL
    // file once: the interleavings below rewrite/delete/plant that file
    // between the two calls - no clock tricks, only disk state.
    // ---------------------------------------------------------------------------

    @Test
    fun `allow-path record refuses bytes that swapped in the gate-record window`() = runBlocking {
        // Gate sees A, the file becomes B before the record lands: the
        // Allow's fingerprint is the ONLY persistable content - B must not
        // ride the Allow's consent in.
        val dir = tmp.newFolder("k")
        val file = File(dir, "crushrc")
        file.writeText("hello")
        val ref = refFor(dir)
        val source = FakeTrustSource()
        val service = serviceWith(source)
        // The Allow-path scenario needs consent to exist first: a present
        // crushrc with NO record is the F04 Required path (first contact),
        // never an Allow. The explicit approval established the consent the
        // gate now re-confirms and the record step refreshes.
        service.recordConsent(ref)
        val verdict = service.gate(ref)
        assertEquals(
            KnownSha.sha256OfHello,
            (verdict as TrustVerdict.Allow).observedFingerprint
        )
        file.writeText("world")
        val outcome = service.confirmAtGate(ref, verdict.observedFingerprint)
        assertEquals(TrustWriteOutcome.ChangedSinceGate, outcome)
        // The explicit consent's record write is the only one that ever
        // happened; the moved state added nothing.
        assertEquals(1, source.writes)
        val next = service.gate(ref)
        assertTrue(next is TrustVerdict.Required)
        assertEquals(
            "crushrc changed since consent (fingerprint moved)",
            (next as TrustVerdict.Required).reason
        )
    }

    @Test
    fun `allow-path record refuses a crushrc that appeared in the gate-record window`() =
        runBlocking {
            val dir = tmp.newFolder("l")
            val ref = refFor(dir)
            val source = FakeTrustSource()
            val service = serviceWith(source)
            val verdict = service.gate(ref) // no file: the no-crushrc Allow
            assertNull((verdict as TrustVerdict.Allow).observedFingerprint)
            File(dir, "crushrc").writeText("hello")
            val outcome = service.confirmAtGate(ref, verdict.observedFingerprint)
            assertEquals(TrustWriteOutcome.FileAppearedSinceGate, outcome)
            assertEquals(
                TrustVerdict.TrustReason.PRESENT_NO_CONSENT,
                outcome.movedTo
            )
            assertTrue("a moved state must not write", source.writes == 0)
            val next = service.gate(ref)
            assertTrue(next is TrustVerdict.Required)
            assertEquals(
                "crushrc present, no consent recorded",
                (next as TrustVerdict.Required).reason
            )
        }

    @Test
    fun `allow-path record persists the OBSERVED fingerprint when the folder is unchanged`() =
        runBlocking {
            val dir = tmp.newFolder("m")
            File(dir, "crushrc").writeText("hello")
            val ref = refFor(dir)
            val source = FakeTrustSource()
            val service = serviceWith(source)
            // Consent exists first (the explicit approval); the gate then
            // re-confirms byte-identical content and the record step
            // refreshes EXACTLY the observed state.
            service.recordConsent(ref)
            val verdict = service.gate(ref)
            assertEquals(
                KnownSha.sha256OfHello,
                (verdict as TrustVerdict.Allow).observedFingerprint
            )
            // Still byte-identical at record time: the allow path STILL
            // records - but only the observed state, and exactly it.
            val outcome = service.confirmAtGate(ref, verdict.observedFingerprint)
            assertEquals(TrustWriteOutcome.UnchangedRecorded, outcome)
            // One write from the explicit consent, one from the allow-path
            // refresh (the gate itself never writes).
            assertEquals(2, source.writes)
            assertEquals(
                KnownSha.sha256OfHello,
                source.trustRecordOf(ref.id)!!.fingerprint
            )
            assertTrue(service.gate(ref) is TrustVerdict.Allow)
        }

    @Test
    fun `allow-path record with the file still absent persists the noConfig state`() =
        runBlocking {
            val dir = tmp.newFolder("n")
            val ref = refFor(dir)
            val source = FakeTrustSource()
            val service = serviceWith(source)
            val verdict = service.gate(ref)
            assertNull((verdict as TrustVerdict.Allow).observedFingerprint)
            val outcome = service.confirmAtGate(ref, verdict.observedFingerprint)
            assertEquals(TrustWriteOutcome.NoFileStillAbsent, outcome)
            assertEquals(1, source.writes)
            assertNull(
                "the noConfig state is what the record rides",
                source.trustRecordOf(ref.id)!!.fingerprint
            )
        }

    @Test
    fun `allow-path record refuses a crushrc that vanished in the gate-record window`() =
        runBlocking {
            val dir = tmp.newFolder("o")
            val file = File(dir, "crushrc")
            file.writeText("hello")
            val ref = refFor(dir)
            val source = FakeTrustSource()
            val service = serviceWith(source)
            // Consent first (explicit approval); the gate then confirms
            // byte-identical content - that is the only true Allow path.
            service.recordConsent(ref)
            val verdict = service.gate(ref)
            val observed = (verdict as TrustVerdict.Allow).observedFingerprint
            file.delete()
            val outcome = service.confirmAtGate(ref, observed)
            assertEquals(TrustWriteOutcome.FileRemovedSinceGate, outcome)
            // The explicit consent's record write is the only one that ever
            // happened; the moved state added nothing.
            assertEquals(1, source.writes)
        }

    @Test
    fun `a stale caller-carried fingerprint is not persisted by the allow-path record`() =
        runBlocking {
            // The window guard decides from the CURRENT read: an observed
            // hash the folder does not (or never did) carry right now writes
            // nothing at all - no record, no refresh.
            val dir = tmp.newFolder("p")
            File(dir, "crushrc").writeText("world") // B was never gate-observed
            val ref = refFor(dir)
            val source = FakeTrustSource()
            val service = serviceWith(source)
            val outcome = service.confirmAtGate(ref, KnownSha.sha256OfHello)
            assertEquals(TrustWriteOutcome.ChangedSinceGate, outcome)
            assertTrue(source.writes == 0)
            assertTrue("no record may exist", source.records.isEmpty())
        }
}
