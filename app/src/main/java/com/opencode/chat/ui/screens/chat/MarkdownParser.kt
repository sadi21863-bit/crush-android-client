package com.opencode.chat.ui.screens.chat

/**
 * A small, streaming-safe Markdown parser.
 *
 * SCOPE, DELIBERATELY
 *
 * This handles what a coding agent actually emits: fenced code, inline code,
 * headings, lists, blockquotes, rules, bold/italic/links. It is not CommonMark
 * and does not try to be. A full implementation would be a large dependency
 * with its own update and supply-chain burden, and the constructs LLMs actually
 * produce in chat are a small fraction of the spec.
 *
 * STREAMING IS THE HARD PART
 *
 * This parser is called on partial text, several times per second, and the
 * final chunk usually arrives mid-construct. Two consequences:
 *
 *  - An UNTERMINATED code fence must still parse as a code block. If it did
 *    not, the opening ``` would flash on screen and the code would render as
 *    prose until the closing fence arrived. [Code] therefore closes at
 *    end-of-input.
 *  - Partial inline markers must not leak. A single trailing `*` renders as a
 *    literal asterisk rather than eating the rest of the line, because
 *    swallowing text is worse than showing a stray character.
 *
 * Neither can be verified by a test that only feeds complete documents, so
 * [MarkdownParserTest] includes partial-input cases for both.
 */
sealed interface MdBlock {
    data class Code(val lang: String?, val lines: List<String>) : MdBlock
    data class Paragraph(val inlines: List<MdInline>) : MdBlock
    data class Heading(val level: Int, val inlines: List<MdInline>) : MdBlock
    data class Bullet(val inlines: List<MdInline>, val indent: Int) : MdBlock
    data class Numbered(val number: Int, val inlines: List<MdInline>, val indent: Int) : MdBlock
    data class Quote(val inlines: List<MdInline>) : MdBlock
    data object Rule : MdBlock
}

sealed interface MdInline {
    data class Text(val text: String) : MdInline
    data class Code(val text: String) : MdInline
    data class Bold(val children: List<MdInline>) : MdInline
    data class Italic(val children: List<MdInline>) : MdInline
    data class Link(val label: List<MdInline>, val url: String) : MdInline
}

object MarkdownParser {

    private val BULLET = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val NUMBERED = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val RULE = Regex("^\\s*([-*_])\\s*(\\1\\s*){2,}$")
    private val FENCE = Regex("^\\s*(?:```|~~~)\\s*([A-Za-z0-9_+#.-]*)\\s*$")

    fun parse(src: String): List<MdBlock> {
        if (src.isEmpty()) return emptyList()
        val out = mutableListOf<MdBlock>()
        val lines = src.lines()
        var i = 0
        // Accumulator for a paragraph broken across several lines.
        val para = mutableListOf<String>()

        fun flushPara() {
            if (para.isNotEmpty()) {
                out += MdBlock.Paragraph(parseInline(para.joinToString("\n")))
                para.clear()
            }
        }

        while (i < lines.size) {
            val line = lines[i]
            val fence = FENCE.find(line)

            if (fence != null) {
                flushPara()
                val lang = fence.groupValues[1].takeIf { it.isNotBlank() }
                val body = mutableListOf<String>()
                var j = i + 1
                var closed = false
                while (j < lines.size) {
                    val l = lines[j]
                    // Closing fence: same marker family, nothing after it.
                    if (l.trim().startsWith("```") || l.trim().startsWith("~~~")) {
                        closed = true
                        break
                    }
                    body += l
                    j++
                }
                // `closed` is intentionally unused as a condition: an unterminated
                // fence is normal mid-stream and must still render as code.
                out += MdBlock.Code(lang, body)
                i = if (closed) j + 1 else j
                continue
            }

            when {
                RULE.matches(line) && line.isNotBlank() -> {
                    flushPara()
                    out += MdBlock.Rule
                }
                HEADING.matches(line) -> {
                    flushPara()
                    val m = HEADING.find(line)!!
                    out += MdBlock.Heading(
                        m.groupValues[1].length,
                        parseInline(m.groupValues[2].trimEnd())
                    )
                }
                BULLET.matches(line) -> {
                    flushPara()
                    val m = BULLET.find(line)!!
                    out += MdBlock.Bullet(
                        parseInline(m.groupValues[2]),
                        indent = m.groupValues[1].length / 2
                    )
                }
                NUMBERED.matches(line) -> {
                    flushPara()
                    val m = NUMBERED.find(line)!!
                    out += MdBlock.Numbered(
                        m.groupValues[2].toIntOrNull() ?: 1,
                        parseInline(m.groupValues[3]),
                        indent = m.groupValues[1].length / 2
                    )
                }
                line.trimStart().startsWith("> ") -> {
                    flushPara()
                    out += MdBlock.Quote(parseInline(line.trimStart().removePrefix("> ")))
                }
                line.isBlank() -> flushPara()
                else -> para += line
            }
            i++
        }
        flushPara()
        return out
    }

    /**
     * Inline parsing.
     *
     * Deliberately not recursive-descent across the whole string: emphasis is
     * resolved with a single left-to-right pass because nested emphasis inside
     * streamed text is rare enough that a full parser would add failure modes
     * (and infinite loops on unbalanced markers) for little gain.
     */
    fun parseInline(src: String): List<MdInline> {
        val out = mutableListOf<MdInline>()
        val buf = StringBuilder()
        var i = 0

        fun flush() {
            if (buf.isNotEmpty()) {
                out += MdInline.Text(buf.toString())
                buf.setLength(0)
            }
        }

        while (i < src.length) {
            val c = src[i]
            when {
                c == '\\' && i + 1 < src.length -> {
                    // Escape: the next character is literal.
                    buf.append(src[i + 1])
                    i += 2
                }
                c == '`' -> {
                    val close = src.indexOf('`', i + 1)
                    // `close == i + 1` means the two backticks are adjacent, i.e.
                    // empty code. Treating that as a code span would emit an
                    // invisible zero-length run, so keep it literal.
                    if (close < 0 || close == i + 1) {
                        buf.append(c)
                        i++
                    } else {
                        flush()
                        out += MdInline.Code(src.substring(i + 1, close))
                        i = close + 1
                    }
                }
                c == '*' -> {
                    // Bold is a PAIR of asterisks. Scanning for a single closing
                    // `*` from inside `**bold**` finds the first half of the
                    // closer and swallows whatever follows - "**bold** and
                    // *italic*" turned the " and " into italic text.
                    if (src.startsWith("**", i)) {
                        val close = src.indexOf("**", i + 2)
                        val inner = if (close > i + 2) src.substring(i + 2, close) else ""
                        if (inner.isEmpty()) {
                            buf.append(c)
                            i++
                        } else {
                            flush()
                            out += MdInline.Bold(parseInline(inner))
                            i = close + 2
                        }
                    } else {
                        val close = src.indexOf('*', i + 1)
                        val inner = if (close > i + 1) src.substring(i + 1, close) else ""
                        // A single `*` with space next to it is arithmetic
                        // ("2 * 3"), not emphasis.
                        if (inner.isEmpty() || inner.first().isWhitespace() || inner.last().isWhitespace()) {
                            buf.append(c)
                            i++
                        } else {
                            flush()
                            out += MdInline.Italic(parseInline(inner))
                            i = close + 1
                        }
                    }
                }
                c == '_' -> {
                    // Underscore emphasis must sit on word boundaries, or every
                    // snake_case identifier loses its underscores: "my_var_name"
                    // became "myvarname".
                    val opensCleanly = i == 0 || !src[i - 1].isLetterOrDigit()
                    val close = src.indexOf('_', i + 1)
                    val closesCleanly = close in 0 until src.length &&
                        (close + 1 >= src.length || !src[close + 1].isLetterOrDigit())
                    val inner = if (close > i + 1) src.substring(i + 1, close) else ""
                    if (!opensCleanly || !closesCleanly || inner.isEmpty() ||
                        inner.first().isWhitespace() || inner.last().isWhitespace()
                    ) {
                        buf.append(c)
                        i++
                    } else {
                        flush()
                        out += MdInline.Italic(parseInline(inner))
                        i = close + 1
                    }
                }
                c == '[' -> {
                    val labelEnd = src.indexOf(']', i)
                    val urlStart = if (labelEnd >= 0 && labelEnd + 1 < src.length &&
                        src[labelEnd + 1] == '('
                    ) src.indexOf(')', labelEnd + 1) else -1
                    if (labelEnd < 0 || urlStart < 0) {
                        buf.append(c)
                        i++
                    } else {
                        flush()
                        out += MdInline.Link(
                            parseInline(src.substring(i + 1, labelEnd)),
                            src.substring(labelEnd + 2, urlStart)
                        )
                        i = urlStart + 1
                    }
                }
                else -> {
                    buf.append(c)
                    i++
                }
            }
        }
        flush()
        return out
    }
}
