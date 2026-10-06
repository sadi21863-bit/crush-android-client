package com.opencode.chat.ui.screens.chat

import com.opencode.chat.data.crush.Session

/**
 * Presentation rules for the session list.
 *
 * Pure so the ordering and labelling can be tested without an engine. The
 * ordering rule that matters: a BUSY session must sort to the top even if it was
 * updated longest ago, because it is the one with a live run attached and hiding
 * it looks like data loss.
 */
object SessionList {

    /**
     * Newest activity first, with busy sessions pinned above everything else.
     *
     * Busy first because an in-flight run is the one session the user is most
     * likely to be looking for, and its updated_at may lag while tokens stream.
     */
    fun sort(sessions: List<Session>): List<Session> =
        sessions.sortedWith(
            compareByDescending<Session> { it.isBusy }
                .thenByDescending { it.updatedAt }
                .thenBy { it.id }
        )

    /**
     * Label for a session row.
     *
     * Crush auto-titles sessions from the first prompt (observed: "Simple
     * Greeting Request"), so the title is usually meaningful. Falls back to the
     * first user message, then to a neutral label, because a blank row in a chat
     * list reads as a rendering bug.
     */
    fun label(session: Session): String {
        session.title.trim().takeIf { it.isNotEmpty() }?.let { return it }
        return if (session.messageCount > 0) {
            "Chat (${session.messageCount} messages)"
        } else {
            "New chat"
        }
    }

    /**
     * A one-line detail under the label. Empty when there is nothing worth
     * showing, so the row can skip the line entirely instead of showing "0
     * messages / $0.00".
     */
    fun detail(session: Session, nowMs: Long): String {
        val bits = mutableListOf<String>()
        val msg = session.messageCount
        if (msg > 0) bits += "$msg msg"

        // cost is a Double and free models legitimately report exactly 0.0, so
        // only spend is worth surfacing.
        if (session.cost > 0.0) bits += "$" + String.format("%.2f", session.cost)

        if (session.isBusy) bits += "running"

        val when_ = relativeTime(session.updatedAt, nowMs)
        if (when_.isNotEmpty()) bits += when_

        return bits.joinToString(" · ")
    }

    /**
     * Coarse relative time. Deliberately small vocabulary: on a phone, "2h" is
     * clearer than a relative-time library's "2 hours ago", and there is no value
     * in second-level precision for a chat list.
     */
    fun relativeTime(epochSec: Long, nowMs: Long): String {
        if (epochSec <= 0L) return ""
        val nowSec = nowMs / 1000L
        val delta = nowSec - epochSec
        // A future timestamp means clock skew, not a negative age.
        if (delta < 0) return "now"
        return when {
            delta < 60 -> "now"
            delta < 3600 -> "${delta / 60}m"
            delta < 86_400 -> "${delta / 3600}h"
            delta < 7 * 86_400 -> "${delta / 86_400}d"
            else -> "${delta / (7 * 86_400)}w"
        }
    }

    /** Total tokens for a session, used for the budget hint. */
    fun tokens(session: Session): Int = session.promptTokens + session.completionTokens

    /**
     * Sessions that are children of [parentId], i.e. forks.
     *
     * Exposed so a future fork UI can group them under their parent rather than
     * flattening them into the top level.
     */
    fun forksOf(sessions: List<Session>, parentId: String): List<Session> {
        // An empty parent id means "top level", not "a session whose parent is
        // the empty string". Without this guard every top-level session matched
        // itself as its own fork.
        if (parentId.isBlank()) return emptyList()
        return sessions.filter { it.parentSessionId == parentId }
    }
}
