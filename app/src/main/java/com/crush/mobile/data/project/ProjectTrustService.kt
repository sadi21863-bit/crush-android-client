package com.crush.mobile.data.project

import com.crush.mobile.project.ProjectRef
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * S-02 (owner pack P0-1; F04/A05; plan shape PR-A): the SINGLE pre-bind
 * consent gate for every path that may bind a project engine and thereby
 * attach its workspace.
 *
 * WHY: the embedded engine executes the project-root `crushrc` as a bash
 * script on EVERY workspace attach (S1b-measured: twice per attach cycle,
 * with the app's full UID). Consent for that is therefore a PER-CONTENT
 * decision, and the F04 dialog must gate not just the interactive row tap
 * but also the cold `restoreLastBinding` path that previously called
 * `applyBinding(Project)` directly - a project whose crushrc was added or
 * changed while the app was dead got it executed with NO consent.
 *
 * The record rides the PROJECT ROW itself (see [RoomProjectStore] /
 * [ProjectEntity]): `trust_fingerprint` + `consented_at_ms`, additively
 * migrated v1->v2. Removing the row removes the consent with it.
 *
 * TIME-OF-CHECK CONTRACT, stated exactly as strong as it is (per the S-02
 * memo): the gate re-hashes the CURRENT file at gate time, and
 * [recordConsent] fingerprints the EXACT crushrc CONTENT at the consent
 * moment. Any content change flips the next gate to Required, so a changed
 * file NEVER executes without a fresh, explicit consent. What is NOT
 * claimed: impossibility of ad hoc swap inside the microscopic window
 * between the gate's hash and the engine's own exec - check-at-bind is the
 * practical contract, not a TOCTOU impossibility proof.
 */
class ProjectTrustService(
    /** The durable record seam; RoomProjectStore is the production impl. */
    private val source: ProjectTrustSource,

    /**
     * Injectable clock stamp. Production uses wall-clock time; the record is
     * a review aid, not a freshness rule (content bytes are the actual key).
     */
    private val stampNow: () -> Long = System::currentTimeMillis,
) {

    // Trust-relevant file name: the ONE project-root file the pinned engine
    // executes (S1b probe: `.crush.json`/`.crush/crush.json` did NOT execute
    // in the paths probed - the shellconfig surface is `crushrc`).
    companion object {
        const val CRUSHRC_FILE_NAME = "crushrc"
    }

    /**
     * THE pre-bind gate. Cheap by design: one file stat + hash of a small
     * text file, one row read. Hops to Dispatchers.IO for the file + store
     * access; safe to call from the main thread.
     */
    suspend fun gate(ref: ProjectRef): TrustVerdict = withContext(Dispatchers.IO) {
        val current = currentFingerprint(ref)
        val record = source.trustRecordOf(ref.id)
        when (record) {
            // Never trusted (fresh row, or a post-removal re-import): a
            // crushrc present needs its first explicit consent; no crushrc
            // is ungated (but see recordConsent - the noConfig state gets
            // recorded at open time so a LATER addition re-gates).
            null ->
                if (current == null) TrustVerdict.Allow(ref)
                else TrustVerdict.Required(ref, TrustVerdict.TrustReason.PRESENT_NO_CONSENT)

            else -> when {
                // Trusted earlier with a NO-crushrc state, file absent UP TO
                // NOW: still the recorded state, still allowed.
                current == null && record.fingerprint == null -> TrustVerdict.Allow(ref)
                // Consent was recorded and the file has since vanished: the
                // user's "I checked this folder" no longer describes what is
                // on disk - re-verify, re-decide.
                current == null ->
                    TrustVerdict.Required(ref, TrustVerdict.TrustReason.REMOVED_SINCE_CONSENT)
                // Consent was only the no-config state; a file exists now:
                // whatever is in it was never consented to.
                record.fingerprint == null ->
                    TrustVerdict.Required(ref, TrustVerdict.TrustReason.PRESENT_NO_CONSENT)
                // The recorded fingerprint IS the executed surface: only
                // byte-identical content passes (lowercase hex, SHA-256).
                record.fingerprint == current -> TrustVerdict.Allow(ref)
                else ->
                    TrustVerdict.Required(ref, TrustVerdict.TrustReason.CHANGED_SINCE_CONSENT)
            }
        }
    }

    /**
     * Records consent bound to the crushrc content AS IT IS right now - the
     * record is written from a FRESH read + hash, never from a
     * caller-supplied string, so a stale fingerprint cannot be persisted
     * past a swap. A folder with NO crushrc records the noConfig trust
     * state, which is exactly what makes a later-planted crushrc re-gate
     * (never silently bind). Callers bind only on gate-Allow or AFTER this
     * call: consent first, engine second.
     */
    suspend fun recordConsent(ref: ProjectRef): String? = withContext(Dispatchers.IO) {
        val fingerprint = currentFingerprint(ref)
        source.writeTrustRecord(ref.id, fingerprint, stampNow())
        fingerprint
    }

    /**
     * SHA-256 hex (lowercase) of the project folder's CURRENT crushrc bytes,
     * or null when the folder carries none. Streams the file in 8 KiB chunks
     * - a readBytes() on a planted multi-GB "crushrc" would be an OOM shaped
     * like a security check failure.
     */
    internal fun currentFingerprint(ref: ProjectRef): String? {
        val file = File(ref.repoDir, CRUSHRC_FILE_NAME)
        if (!file.isFile) return null
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(8192)
            var read = ins.read(buf)
            while (read > 0) {
                md.update(buf, 0, read)
                read = ins.read(buf)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * The gate's verdict, in the house sealed-outcome shape. [Allow] is a
 * permission to bind THIS ref as of this gate call; [Required] is an
 * unconditional stop - the caller must NOT bind and must raise the F04
 * consent dialog.
 */
sealed interface TrustVerdict {
    val ref: ProjectRef

    data class Allow(override val ref: ProjectRef) : TrustVerdict

    data class Required(override val ref: ProjectRef, val kind: TrustReason) : TrustVerdict {
        /** The shaped reason string (log + dialog surface, pack wording). */
        val reason: String get() = kind.text
    }

    /**
     * The three reason shapes the S-02 plan names. [text] is host-owned
     * security copy wording, surfaced verbatim in the honest-pair log line.
     */
    enum class TrustReason(val text: String) {
        PRESENT_NO_CONSENT("crushrc present, no consent recorded"),
        CHANGED_SINCE_CONSENT("crushrc changed since consent (fingerprint moved)"),
        REMOVED_SINCE_CONSENT("crushrc removed since consent (re-verify folder state)"),
    }
}

/**
 * The persisted consent state for ONE project row. Null
 * [fingerprint] = the folder was trusted as a NO-crushrc folder (so a file
 * that appears later re-gates); non-null = SHA-256 hex of the consented
 * bytes at consent time. Removing the row removes the record with it
 * (columns on the row - see [ProjectEntity]).
 */
data class ProjectTrustRecord(
    val fingerprint: String?,
    val consentedAtMs: Long,
)

/**
 * Read/write seam for the per-row trust record, deliberately tiny:
 * [RoomProjectStore] implements it over the project row's own columns, and
 * the JVM matrix test tier substitutes an in-memory map so the verdict logic
 * is testable with zero Android machinery.
 */
interface ProjectTrustSource {
    /** The current record for a row, or null when never trusted. */
    suspend fun trustRecordOf(id: UUID): ProjectTrustRecord?

    /** Writes (or replaces) the record. Production rows only ever GAIN a record. */
    suspend fun writeTrustRecord(id: UUID, fingerprint: String?, consentedAtMs: Long)
}

/**
 * A pending consent decision published by the container when a COLD restore
 * was blocked by the gate (S-02): "do not bind; let the UI prompt at
 * leisure". Drives the root-level F04 dialog host (MainActivity, the same
 * pattern [com.crush.mobile.ui.screens.lock.LockOverlay] rides - a dialog
 * over whatever surface the cold boot landed on, not a route).
 */
data class PendingProjectTrust(
    val ref: ProjectRef,
    val kind: TrustVerdict.TrustReason,
) {
    /** The shaped reason string, for log parity with the gate verdict. */
    val reason: String get() = kind.text
}
