package com.crush.mobile.ui.blocks

/**
 * V2-8 experiment helper: model replies often wrap JSON in a single Markdown
 * fence even when asked not to. The lab strips exactly one outer fence; it does
 * not search for JSON in prose, repair malformed payloads, or accept partial
 * documents. Everything else goes to the validator unchanged.
 */
fun normalizeUiBlockPayload(raw: String): String {
    val text = raw.trim()
    if (!text.startsWith("```") || !text.endsWith("```")) return text
    val firstNewline = text.indexOf('\n')
    if (firstNewline < 0) return text
    val body = text.substring(firstNewline + 1, text.length - 3).trim()
    return body.ifBlank { text }
}
