package com.crush.mobile.data.handoff

import android.content.Context
import com.crush.mobile.data.project.RoomProjectStore
import com.crush.mobile.domain.handoff.HandoffPackageDraft
import com.crush.mobile.ui.screens.chat.ChatMessage
import com.crush.mobile.ui.screens.chat.handoffUsableExistingDraft
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * P6 Batch C dispatch-route proofs, JVM-Robolectric tier (the
 * HandoffRoomRoundtripTest discipline: an own .db file, never the production
 * projects.db). These pin the WRITE ORDER `confirmHandoff` performs - the
 * seam where the Batch C consent sheet lands on the Batch A status machine:
 *
 * 1. Confirm-with-project writes a DRAFT row (fingerprint NULL - Batch F
 *    wires the stat walk; the row stores null until then per the DAO's
 *    contract) and stamps it APPROVED through the guarded transition.
 * 2. A dispatch that fails BEFORE session creation never stamps DISPATCHED -
 *    the row stays APPROVED, traceable and resumable (plan e1; the Batch A
 *    guarantee the brief's test tier (c) names), and it survives a re-opened
 *    store (the row is the cross-restart trace).
 * 3. The successful dispatch stamps exactly once, carrying the fresh
 *    session id and a null fingerprint, with the approval stamp surviving
 *    untouched.
 * 4. Cancel BEFORE confirm inserts nothing - "never inserted" is the cancel
 *    mapping, so the trace view for that source chat reads empty.
 *
 * WINDOWS NAME-BUDGET NOTE (2026-11-09 diagnosis): Robolectric derives the
 * per-test temp dir from the FULL test method name
 * (robolectric-<Class>_<method name><random>\...\databases\<dbName>), so a
 * long method name pushes the SQLite file path past Windows' 260-char
 * MAX_PATH and open fails as SQLITE_CANTOPEN "unable to open database
 * file" - which masquerades as a cross-test file lock. Keep these method
 * names <= ~35 chars.
 */
@RunWith(RobolectricTestRunner::class)
class HandoffDispatchSequencingTest {

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    /** Test-owned database name - deliberately NOT the production file. */
    private val dbName = "handoffs-dispatch-sequencing-test.db"

    @Before
    fun wipeTestDatabase() {
        context.deleteDatabase(dbName)
        context.getDatabasePath(dbName).parentFile?.let { dir ->
            listOf("-wal", "-shm", "-journal").forEach { suffix ->
                File(dir, "$dbName$suffix").delete()
            }
        }
    }

    private fun newStore() = RoomProjectStore(context, dbName)

    /**
     * The sheet's own tap->seal chain, reduced to the pure product Confirm
     * writes: Batch B's prefill over the transcript the bubble tap prefixes,
     * sealed onto an ADR-0001 targets row id, with the fingerprint arg left
     * null exactly as the ViewModel does for the DRAFT write.
     */
    private fun sealedEntity(sourceChatId: String, targetId: String): HandoffEntity =
        HandoffPackageDraft.prefill(
            messages = listOf(
                ChatMessage(id = "u1", role = "user", text = "plan the split"),
                ChatMessage(
                    id = "a1",
                    role = "assistant",
                    text = "Here is the plan:\n1. split the ledger\n2. keep totals",
                ),
            ),
            sourceSessionTitle = "Ledger refactor",
        ).copy(sourceChatId = sourceChatId)
            .toPackage(targetId)
            .toEntity(null)

    private suspend fun approve(dao: HandoffDao, entity: HandoffEntity, stampMs: Long): Int {
        dao.insertDraft(entity)
        return dao.markApproved(entity.handoffId, stampMs)
    }

    @Test(timeout = 120_000)
    fun `confirm through the guarded approve`() =
        runBlocking {
            val store = newStore()
            val dao = store.handoffDao()
            val entity = sealedEntity("ses-7", "target-A")

            // Cancel-before-confirm wrote nothing: the insert is the FIRST write
            // this flow ever makes.
            assertNull(dao.latestDraftForSource("ses-7"))

            dao.insertDraft(entity)
            val row = dao.getById(entity.handoffId) ?: error("row must exist")
            assertEquals("DRAFT", row.status)
            assertNull("no approval stamp before Confirm", row.userApprovedAt)
            assertNull("targetCodeSessionId is NULL until DISPATCHED", row.targetCodeSessionId)
            assertNull(
                "the fingerprint column stays NULL: Batch F wires the stat walk; " +
                    "the row stores null until then per the DAO's contract",
                row.projectTreeFingerprintAtHandoff,
            )
            assertEquals("Here is the plan:\n1. split the ledger\n2. keep totals", row.summaryMarkdown)
            assertEquals("ses-7", row.sourceChatId)
            assertEquals("target-A", row.targetProjectId)
            assertEquals("NONE", row.expiryPolicy)
            assertEquals("", row.selectedReferences)

            val approvedAt = 1_111L
            assertEquals("the DRAFT -> APPROVED transition is a single guarded stamp", 1,
                dao.markApproved(entity.handoffId, approvedAt))
            val approved = dao.getById(entity.handoffId) ?: error("row must exist")
            assertEquals("APPROVED", approved.status)
            assertEquals(approvedAt, approved.userApprovedAt)
            assertNull("still no target session - dispatch owns that column", approved.targetCodeSessionId)

            // The guarded machine holds around the sheet's whole write order: a
            // second approve attempt (a double-tap, a stale sheet) is a no-op
            // against the state the row is no longer in.
            assertEquals(0, dao.markApproved(entity.handoffId, 2_222L))
            assertEquals("the first stamp survives", approvedAt, dao.getById(entity.handoffId)?.userApprovedAt)
        }

    @Test(timeout = 120_000)
    fun `a failed dispatch stays resumable`() =
        runBlocking {
            val store = newStore()
            val dao = store.handoffDao()
            val entity = sealedEntity("ses-7", "target-A")
            approve(dao, entity, 5_000L)

            // The dispatch failed before WorkspaceManager.newSession could
            // return a session: no markDispatched call ever happens. The row
            // therefore stays APPROVED - traceable, never a half-claimed
            // DISPATCHED - and the guarded machinery keeps it re-openable:
            // a LATER successful dispatch again stamps from APPROVED.
            val failed = dao.getById(entity.handoffId) ?: error("row must exist")
            assertEquals("APPROVED", failed.status)
            assertNull("a failed dispatch wrote no target session id", failed.targetCodeSessionId)
            assertNull(failed.projectTreeFingerprintAtHandoff)

            // Resumability, enforced by the same SQL the sheet row lives on:
            // the approved row can still take the guarded DISPATCHED stamp
            // (that is what "resumable from the row" means) - and once it does,
            // the stamp is single-shot and the trace view carries it.
            assertEquals(1, dao.markDispatched(entity.handoffId, "ses-resumed-1", null))
            assertEquals(0, dao.markDispatched(entity.handoffId, "ses-never-2", null))
            val resumed = dao.getById(entity.handoffId) ?: error("row must exist")
            assertEquals("ses-resumed-1", resumed.targetCodeSessionId)
            assertNull("the deferred fingerprint stays null even at the resume stamp", resumed.projectTreeFingerprintAtHandoff)

            // The row is the cross-restart trace: a NEW store over the SAME
            // file (what the next process's DI constructs) reads it verbatim.
            val restarted = newStore()
            val trace = restarted.handoffDao().getById(entity.handoffId) ?: error("trace must survive")
            assertEquals("DISPATCHED", trace.status)
            assertEquals("ses-resumed-1", trace.targetCodeSessionId)
            assertEquals("Here is the plan:\n1. split the ledger\n2. keep totals", trace.summaryMarkdown)
            assertNull(trace.projectTreeFingerprintAtHandoff)
        }

    @Test(timeout = 120_000)
    fun `cancel before confirm inserts nothing`() = runBlocking {
        val dao = newStore().handoffDao()
        // A cancel destroys the draft through silence: no insert ever happened,
        // so nothing needs abandoning and no ABANDONED row exists either (the
        // explicit post-approval abandon is Batch D's move on an ALREADY-written
        // row). The mapping decision: cancel == never inserted.
        val before = dao.latestDraftForSource("ses-7")
        assertNull(before)
        assertTrue("no rows at all", dao.observeLatest().first().isEmpty())
        // The abandon path is NOT part of the cancel flow: there is no row id
        // to abandon, and calling it blind would forge a trace. (Latest-draft
        // lookups confirm the sheet reopens clean, prefill-only.)
        assertNull(dao.latestDraftForSource("ses-7"))
    }

    @Test(timeout = 120_000)
    fun `untouched draft rewrite covenant`() =
        runBlocking {
            val store = newStore()
            val dao = store.handoffDao()
            val first = sealedEntity("ses-7", "target-A")
            dao.insertDraft(first)

            // The composable contract's in-place rewrite (untouched covenant):
            // same handoffId, edited summary + a changed target - this is the
            // ONE route a target change may travel before approval.
            val rewritten = first.copy(
                summaryMarkdown = "the user's edited summary",
                targetProjectId = "target-B",
            )
            dao.insertDraft(rewritten)
            val row = dao.getById(first.handoffId) ?: error("row must exist")
            assertEquals("the user's edited summary", row.summaryMarkdown)
            assertEquals("target-B", row.targetProjectId)
            assertEquals("DRAFT", row.status)
            assertEquals("one row, not a twin", 1, dao.observeLatest().first().size)
            assertEquals("the current-draft lookup resolves to THE row", first.handoffId, dao.latestDraftForSource("ses-7")?.handoffId)

            // Once the row moved on, the composer-level validation (the exact
            // predicate confirm reuses) refuses a further in-place rewrite -
            // pinned here against the REAL stamped row instead of a fixture.
            assertEquals(1, dao.markApproved(first.handoffId, 7_000L))
            val approved = dao.getById(first.handoffId) ?: error("row must exist")
            assertEquals("APPROVED", approved.status)
            assertNull("still un-dispatched: resumable", approved.targetCodeSessionId)
            assertFalse("the stamped row is out of the rewrite covenant", handoffUsableExistingDraft(approved))
        }

    // --------------------------------------------------- P6 Batch D rows
    // The post-approval surface: abandon (the explicit move), the no-alias
    // replace, the trace classification read, and the resume stamp's
    // approval-survival. Same tier, same ~35-char method-name budget.

    @Test(timeout = 120_000)
    fun `discard abandons approved`() =
        runBlocking {
            val store = newStore()
            val dao = store.handoffDao()
            val entity = sealedEntity("ses-7", "target-A")
            approve(dao, entity, 5_000L)

            // The explicit post-approval abandon (Batch D): DRAFT and
            // APPROVED are the allowed FROM states. Only status moves; the
            // approval stamp and the sealed package stay as the record of
            // what was approved (the "orientable ABANDONED row").
            assertEquals(1, dao.abandon(entity.handoffId))
            val row = dao.getById(entity.handoffId) ?: error("row must exist")
            assertEquals("ABANDONED", row.status)
            assertEquals("the approval stamp survives the abandon", 5_000L, row.userApprovedAt)
            assertEquals("the approved target survives", "target-A", row.targetProjectId)
            assertEquals(
                "the approved summary survives",
                "Here is the plan:\n1. split the ledger\n2. keep totals",
                row.summaryMarkdown,
            )
            assertNull("no target session was ever written", row.targetCodeSessionId)
            assertNull(row.projectTreeFingerprintAtHandoff)

            // Traces are append-only: an ABANDONED row cannot re-enter the
            // machine through any guarded transition.
            assertEquals(0, dao.abandon(entity.handoffId))
            assertEquals(0, dao.markApproved(entity.handoffId, 9_000L))
            assertEquals("ABANDONED", dao.getById(entity.handoffId)?.status)
        }

    @Test(timeout = 120_000)
    fun `discard refused on dispatched`() =
        runBlocking {
            val store = newStore()
            val dao = store.handoffDao()
            val entity = sealedEntity("ses-7", "target-A")
            approve(dao, entity, 5_000L)
            assertEquals(1, dao.markDispatched(entity.handoffId, "ses-disp-1", null))

            // A DISPATCHED row can NEVER be abandoned - it is the durable
            // cross-restart trace the resume route reads exactly in this
            // state: the guard returns 0 and every column stays verbatim.
            assertEquals(0, dao.abandon(entity.handoffId))
            val row = dao.getById(entity.handoffId) ?: error("row must exist")
            assertEquals("DISPATCHED", row.status)
            assertEquals("ses-disp-1", row.targetCodeSessionId)
            assertEquals(5_000L, row.userApprovedAt)
            assertEquals("target-A", row.targetProjectId)
        }

    @Test(timeout = 120_000)
    fun `rows-for-source newest-first all`() =
        runBlocking {
            val dao = newStore().handoffDao()
            // One source's three-shape history: a stale ABANDONED head over
            // a live APPROVED trace over the oldest untouched draft.
            val oldest = sealedEntity("ses-7", "target-A")
            dao.insertDraft(oldest)
            val live = sealedEntity("ses-7", "target-B")
            dao.insertDraft(live)
            assertEquals(1, dao.markApproved(live.handoffId, 2_000L))
            val stale = sealedEntity("ses-7", "target-C")
            dao.insertDraft(stale)
            assertEquals(1, dao.abandon(stale.handoffId))

            val rows = dao.rowsForSource("ses-7")
            assertEquals(
                "newest first across EVERY status",
                listOf(stale.handoffId, live.handoffId, oldest.handoffId),
                rows.map { it.handoffId },
            )
            assertTrue(
                "the classification needs every status in one list",
                rows.map { it.status }.containsAll(listOf("ABANDONED", "APPROVED", "DRAFT")),
            )
            assertTrue("other sources never bleed in", dao.rowsForSource("ses-other").isEmpty())
        }

    @Test(timeout = 120_000)
    fun `no-alias replace path`() =
        runBlocking {
            val dao = newStore().handoffDao()
            // Plan :416 at the DAO level: approved@target-A; a destination
            // change abandons it and a NEW handoffId re-approves - the old
            // approval never transfers in place.
            val old = sealedEntity("ses-7", "target-A")
            dao.insertDraft(old)
            assertEquals(1, dao.markApproved(old.handoffId, 5_000L))

            assertEquals(1, dao.abandon(old.handoffId))
            val fresh = sealedEntity("ses-7", "target-B")
            dao.insertDraft(fresh)
            assertEquals(1, dao.markApproved(fresh.handoffId, 6_000L))
            assertEquals(1, dao.markDispatched(fresh.handoffId, "ses-new-1", null))

            val oldRow = dao.getById(old.handoffId) ?: error("row must exist")
            assertEquals("ABANDONED", oldRow.status)
            assertEquals("the old approval stamp stays intact", 5_000L, oldRow.userApprovedAt)
            assertEquals("the old target stays intact", "target-A", oldRow.targetProjectId)
            val freshRow = dao.getById(fresh.handoffId) ?: error("row must exist")
            assertEquals("DISPATCHED", freshRow.status)
            assertEquals("target-B", freshRow.targetProjectId)
            assertEquals("ses-new-1", freshRow.targetCodeSessionId)
            assertEquals("two rows, never a rewritten one", 2, dao.observeLatest().first().size)
        }

    @Test(timeout = 120_000)
    fun `dispatch stamps the tree digest`() =
        runBlocking {
            val dao = newStore().handoffDao()
            val entity = sealedEntity("ses-7", "target-A")
            approve(dao, entity, 42_000L)
            val digest = "a".repeat(64)

            // Batch F: the AT-dispatch stat digest lands in the row column
            // (the "(not recorded)" NULL remains the WALK-FAILURE shape,
            // never the normal dispatch shape).
            assertEquals(1, dao.markDispatched(entity.handoffId, "ses-1", digest))
            val row = dao.getById(entity.handoffId) ?: error("row must exist")
            assertEquals("DISPATCHED", row.status)
            assertEquals("the digest rides the guarded stamp", digest, row.projectTreeFingerprintAtHandoff)
            assertEquals("the approval stamp is untouched by the digest", 42_000L, row.userApprovedAt)

            // Single-shot even with the same id: a second stamp can never
            // rewrite the recorded digest (or the session it was taken with).
            assertEquals(0, dao.markDispatched(entity.handoffId, "ses-2", null))
            assertEquals(digest, dao.getById(entity.handoffId)?.projectTreeFingerprintAtHandoff)
        }

    @Test(timeout = 120_000)
    fun `resume stamp keeps approval`() =
        runBlocking {
            val dao = newStore().handoffDao()
            val entity = sealedEntity("ses-7", "target-A")
            approve(dao, entity, 77_000L)

            // The Batch D resume dispatches ON the existing approval: the
            // DISPATCHED stamp adds only the session id - the approval stamp
            // is the ORIGINAL one, never rewritten (the first-turn template
            // renders exactly what this carries).
            assertEquals(1, dao.markDispatched(entity.handoffId, "ses-resume-1", null))
            val row = dao.getById(entity.handoffId) ?: error("row must exist")
            assertEquals("DISPATCHED", row.status)
            assertEquals("the ORIGINAL approval stamp rides the resume", 77_000L, row.userApprovedAt)
            assertEquals("ses-resume-1", row.targetCodeSessionId)
            assertNull("the fingerprint deferral stands at resume too", row.projectTreeFingerprintAtHandoff)
        }
}
