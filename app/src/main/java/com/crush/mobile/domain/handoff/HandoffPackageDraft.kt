package com.crush.mobile.domain.handoff

import com.crush.mobile.ui.screens.chat.ChatMessage

/**
 * The compose-time handoff draft (Batch B, `build-plan-handoff-p6-2026-11-08`
 * §4 b2/b3): the summary the user reviews and edits, the snippets they
 * EXPLICITLY attach, and the provenance the consent preamble renders.
 *
 * Content law (plan :407 "must not copy secrets or the full transcript
 * automatically", design §4(2)): the ONLY automatic entry is the last
 * completed assistant message's text - the exact text the bubble's Copy
 * action already hands the user (MessageActionsRow copies the same m.text,
 * ChatScreen.kt:592-593) - so the sheet never shows more than the user could
 * copy. Everything else arrives from explicit user action: nothing outside
 * this class's constructor and copy() is able to mint [selectedReferences],
 * and no API in this package harvests the transcript.
 *
 * DEGRADE-TO-EMPTY-EDITABLE (design §4(1), verbatim): "if the chat engine is
 * not attached, the composer opens EMPTY and stays fully editable - the flow
 * never depends on a live engine." Build-time corollary (plan b3): the
 * prefill is a pure selection over already-fetched rows - best-effort from
 * the caller's side (the ChatViewModel.kt:1825 history-replay call shape - the
 * plan cites :1752, the pre-P6 tree) - and a failure, an
 * absent engine or an absent assistant turn all yield the SAME result: an
 * empty, fully editable summary on a draft that still exists (the draft is
 * app-side state, not engine state).
 */
data class HandoffPackageDraft(
    /** The user-editable summary; empty in the degraded open. */
    val summaryMarkdown: String,

    /**
     * The code-block snippet texts the user explicitly attached (the exact
     * text the composer's code-block Copy/Save chips extract,
     * MarkdownText.kt:177/:186 via codeBlockContent). COMES ONLY FROM
     * EXPLICIT USER SELECTION - nothing here auto-selects a snippet.
     */
    val selectedReferences: List<String> = emptyList(),

    /**
     * The app's best-known source chat id, supplied by the composer when it
     * is known (the prefill factory cannot know it - the selection runs on a
     * plain transcript). Sealing requires it: the row's sourceChatId column
     * is NOT NULL and HandoffDao keys the current-draft lookup on it.
     */
    val sourceChatId: String? = null,

    /** Display provenance: the source session's title as the app knows it. */
    val sourceChatTitle: String = "",

    /**
     * Display provenance: a caller-ready timestamp string for the source
     * conversation (the ui ChatMessage carries no clock, so the composer
     * supplies what its session list knows).
     */
    val sourceChatTime: String? = null,

    /**
     * Which provider/model served the source chat, when the composer knows
     * it. Its exact app-readable origin is the build plan's §12 owner fact
     * 2, so this stays null = unresolved; sealing renders "" the row's NOT
     * NULL column can hold until a resolved value replaces it.
     */
    val sourceProvider: String? = null,
) {

    /**
     * Seals the draft into the [HandoffPackage] that carries the
     * user-approved content to the row and to dispatch. [targetProjectId]
     * is the ADR-0001 stable Room row id - never a label. [sourceChatId]
     * must have been supplied by the composer: the degrade path passes the
     * app's best-known id (b3's "the draft row still exists" without a live
     * engine), never a fabricated engine reference. The handoffId mints
     * here, once, at approval shape - a row rewrite with a different id
     * would violate the no-alias rule (plan :416); the DAO owns every
     * status transition from this point.
     */
    fun toPackage(targetProjectId: String): HandoffPackage {
        val chatId = requireNotNull(sourceChatId) {
            "a handoff draft seals only with the app's best-known source chat id"
        }
        return HandoffPackage(
            sourceChatId = chatId,
            targetProjectId = targetProjectId,
            summaryMarkdown = summaryMarkdown,
            selectedReferences = selectedReferences,
            sourceChatTitle = sourceChatTitle,
            sourceChatTime = sourceChatTime,
            sourceProvider = sourceProvider ?: "",
        )
    }

    /**
     * The pure redaction gate the composer runs before approval (Batch B
     * b4; design §5 "package builder redactions - nothing secret-shaped;
     * nothing not shown in the composer"): scans the summary and every
     * selected reference for MACHINE-GENERATED secret shapes and returns
     * [RedactionVerdict.Green] or [RedactionVerdict.Blocked] naming one
     * pattern. The net is deliberately conservative - specific high-density
     * shapes only (see [SECRET_SHAPES], each documented with its why) -
     * because over-redaction would fight the user over text they legitimately
     * hold, while false negatives let a secret into the Code session.
     * Deliberately NOT scanned: bulk (the plan's "no full transcript" rule
     * bans AUTOMATIC copying, and the user may paste whatever they can
     * already copy) and assignment-style lines (`password = "..."`) whose
     * value is not itself machine-secret-shaped.
     *
     * No match VALUE is ever echoed back in the verdict: a reason that
     * repeats the secret would move the secret into another surface's
     * state.
     */
    fun verifyContent(): RedactionVerdict {
        (listOf(summaryMarkdown) + selectedReferences).forEach { text ->
            SECRET_SHAPES.forEach { shape ->
                if (shape.pattern.containsMatchIn(text)) {
                    return RedactionVerdict.Blocked(shape.id, shape.why)
                }
            }
        }
        return RedactionVerdict.Green
    }

    companion object {

        /**
         * Prefills the draft from the source chat's transcript rows.
         *
         * Selection rule (plan b2, applied to the ui ChatMessage rows the
         * chat screen already renders): the candidate is the LAST row with
         * role "assistant" - non-assistant rows are ignored as candidates -
         * and the candidate must pass the completed-assistant gate, the
         * SAME conjunction that decides when the bubble's Copy action
         * exists (ChatScreen.kt:592): streaming rows are incomplete, rows
         * carrying an `error` are the failed turn (the replay path maps a
         * finish-reason-error row to error != null, ChatViewModel.replayRow),
         * and empty text has nothing to hand over. If the candidate fails
         * the gate - or no assistant row exists at all - the summary
         * prefills EMPTY and stays fully editable. A failed last turn
         * NEVER falls back to an earlier assistant turn: the user reviews
         * the plan their LAST completed exchange produced, and anything
         * else would prefill content the composer never showed as the
         * current answer.
         *
         * [sourceSessionTitle] always lands as [sourceChatTitle]: provenance
         * rides the app's session list, not the engine transcript, so it is
         * carried in the healthy AND the degraded case alike.
         */
        fun prefill(
            messages: List<ChatMessage>,
            sourceSessionTitle: String,
        ): HandoffPackageDraft {
            val candidate = messages.lastOrNull { it.role == "assistant" }
            val prefillText = candidate
                ?.takeIf { !it.isStreaming && it.error == null && it.text.isNotEmpty() }
                ?.text
                ?: ""
            return HandoffPackageDraft(
                summaryMarkdown = prefillText,
                sourceChatTitle = sourceSessionTitle,
            )
        }
    }
}

/**
 * [HandoffPackageDraft.verifyContent]'s verdict: green, or blocked with the
 * id of the pattern that fired (see [SECRET_SHAPES] - ids are the stable
 * vocabulary the composer's explanation shows) and its why.
 */
sealed interface RedactionVerdict {
    object Green : RedactionVerdict
    data class Blocked(val patternId: String, val reason: String) : RedactionVerdict
}

/** One conservative secret shape: its stable id, matcher, and rationale. */
private data class SecretShape(val id: String, val pattern: Regex, val why: String)

/**
 * The redaction net, ORDERED most-specific first so a hit reports the
 * precise shape it matched - [long-hex-secret] is deliberately LAST as
 * the catch-all consulted only when no named shape explained the hit first.
 *
 * Each pattern targets a machine-generated shape (never prose vocabulary):
 *
 * - pem-private-key: a pasted `-----BEGIN ... PRIVATE KEY-----` block is
 *   credential MATERIAL verbatim (RSA/OpenSSH/EC/ENCRYPTED variants); no
 *   prose writes that header, so the false positive cost is nil and the
 *   content it would smuggle is devastating.
 * - bearer-token: "Authorization: Bearer <token>" copied out of code,
 *   headers or logs is a live credential verbatim; the scheme word plus an
 *   opaque 16+ char run is high-confidence. Ordinary prose about bearer
 *   tokens ("Bearer authentication", "Bearer token") carries no qualifying
 *   opaque run.
 * - api-key-literal: the vendor-assigned key prefixes - sk- (OpenAI
 *   family), ghp_/gho_/ghu_/ghs_/ghr_ (GitHub), AKIA + 16 uppercase
 *   (AWS access key id), xoxp/xoxa/xoxb/xoxs/xoxr (Slack). The prefixes are
 *   machine-minted, so prose cannot collide (the leading \b keeps
 *   "risk-assessment..." from matching through its "sk-" substring).
 * - jwt-token: the three dotted base64url segments whose first one starts
 *   eyJ (the base64 of `{"`) - a serialized JWS/JWT. Header-carried tokens
 *   are already caught by bearer-token; this catches the stashed-in-code
 *   form.
 * - long-hex-secret: 32-64 contiguous hex chars bounded both ends. Modern
 *   secrets, keys and digests live in this band (32=MD5-scale, 40-64 =
 *   SHA-1/SHA-256-scale), and a random hex run of that length is not
 *   prose. DECLARED false positives, accepted on purpose: a git SHA quoted
 *   in a summary, an all-hex unhyphenated uuid, a fixture id - the exact
 *   FP classes the plan accepts because the opposite miss leaks a live
 *   secret into the target.
 */
private val SECRET_SHAPES = listOf(
    SecretShape(
        id = "pem-private-key",
        pattern = Regex("(?i)-----BEGIN [A-Za-z0-9 ]*PRIVATE KEY-----"),
        why = "a pasted '-----BEGIN ... PRIVATE KEY-----' block is key material, " +
            "not text - remove the key block from the handoff content.",
    ),
    SecretShape(
        id = "bearer-token",
        pattern = Regex("\\bbearer\\s+[A-Za-z0-9._~+/=-]{16,}", setOf(RegexOption.IGNORE_CASE)),
        why = "an 'Authorization: Bearer ...' credential run was carried in - " +
            "remove the token from the handoff content.",
    ),
    SecretShape(
        id = "api-key-literal",
        pattern = Regex(
            "\\b(sk-[A-Za-z0-9_-]{16,}|gh[pousr]_[A-Za-z0-9]{20,}|" +
                "AKIA[0-9A-Z]{16}|xox[baprs]-[A-Za-z0-9-]{10,})"
        ),
        why = "a provider API key literal (sk- / ghp_ / AKIA / xox shape) was " +
            "carried in - remove the key from the handoff content.",
    ),
    SecretShape(
        id = "jwt-token",
        pattern = Regex("\\beyJ[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{10,}"),
        why = "a serialized JWT (three base64url segments) was carried in - " +
            "remove the token from the handoff content.",
    ),
    SecretShape(
        id = "long-hex-secret",
        pattern = Regex("\\b[a-fA-F0-9]{32,64}\\b"),
        why = "a 32-64 char hex run was carried in - machine secrets wear this " +
            "shape; remove it unless it is a harmless hash you accept sending.",
    ),
)
