package com.crush.mobile.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Markdown parsing, with emphasis on PARTIAL input.
 *
 * The parser runs on streaming text several times a second, so the final chunk
 * almost always arrives mid-construct. A parser that is only ever tested on
 * complete documents will pass here and then flash raw ``` markers on screen
 * mid-reply, which is exactly the kind of visible bug that makes an app feel
 * broken.
 */
class MarkdownParserTest {

    // ---- code blocks: the case that matters most for a coding agent ------

    @Test
    fun `a closed fenced block captures language and body`() {
        val b = MarkdownParser.parse("```kotlin\nval x = 1\nval y = 2\n```")
        assertEquals(1, b.size)
        val code = b[0] as MdBlock.Code
        assertEquals("kotlin", code.lang)
        assertEquals(listOf("val x = 1", "val y = 2"), code.lines)
    }

    @Test
    fun `an unterminated fence still parses as code`() {
        // The streaming case: the opening fence has arrived, the closing one
        // has not. It must render as code, not leak ``` as prose.
        val b = MarkdownParser.parse("```python\nprint(1)\nprint(")
        assertEquals(1, b.size)
        val code = b[0] as MdBlock.Code
        assertEquals("python", code.lang)
        assertEquals(listOf("print(1)", "print("), code.lines)
    }

    @Test
    fun `an empty unterminated fence is still a code block`() {
        val b = MarkdownParser.parse("```")
        assertTrue(b[0] is MdBlock.Code)
    }

    @Test
    fun `a fence with no language is allowed`() {
        val code = MarkdownParser.parse("```\nplain\n```")[0] as MdBlock.Code
        assertEquals(null, code.lang)
        assertEquals(listOf("plain"), code.lines)
    }

    @Test
    fun `tilde fences work too`() {
        val code = MarkdownParser.parse("~~~js\nlet a = 1\n~~~")[0] as MdBlock.Code
        assertEquals("js", code.lang)
    }

    @Test
    fun `text before a fence stays a separate paragraph`() {
        val b = MarkdownParser.parse("Here you go:\n\n```\ncode\n```")
        assertTrue(b[0] is MdBlock.Paragraph)
        assertTrue(b.any { it is MdBlock.Code })
    }

    @Test
    fun `indentation inside code is preserved exactly`() {
        // Re-indenting code silently changes it.
        val code = MarkdownParser.parse("```\n    indented()\n\ttabbed()\n```")[0] as MdBlock.Code
        assertEquals(listOf("    indented()", "\ttabbed()"), code.lines)
    }

    @Test
    fun `two code blocks stay separate`() {
        val b = MarkdownParser.parse("```\na\n```\ntext\n```\nb\n```")
        assertEquals(2, b.count { it is MdBlock.Code })
    }

    // ---- inline code ------------------------------------------------------

    @Test
    fun `inline code is captured`() {
        val inl = MarkdownParser.parseInline("use `npm test` now")
        assertTrue(inl.any { it is MdInline.Code && it.text == "npm test" })
    }

    @Test
    fun `an unterminated backtick stays literal`() {
        // Swallowing the rest of the line would be worse than a stray character.
        val inl = MarkdownParser.parseInline("a ` b")
        val text = inl.filterIsInstance<MdInline.Text>().joinToString("") { it.text }
        assertTrue("got: $text", text.contains("`"))
    }

    @Test
    fun `empty inline code does not vanish`() {
        val inl = MarkdownParser.parseInline("a``b")
        assertTrue(inl.none { it is MdInline.Code && it.text.isEmpty() })
    }

    // ---- emphasis ---------------------------------------------------------

    @Test
    fun `bold and italic are separated`() {
        val inl = MarkdownParser.parseInline("**bold** and *italic*")
        assertTrue(inl.any { it is MdInline.Bold })
        assertTrue(inl.any { it is MdInline.Italic })
    }

    @Test
    fun `a lone asterisk does not eat the line`() {
        val text = MarkdownParser.parseInline("2 * 3 = 6")
            .filterIsInstance<MdInline.Text>().joinToString("") { it.text }
        assertTrue("got: $text", text.contains("*"))
    }

    @Test
    fun `underscore emphasis works but snake_case survives`() {
        val inl = MarkdownParser.parseInline("use my_var_name here")
        // A single _ with content between would otherwise become italic; the
        // common identifier case must not be mangled.
        val joined = inl.joinToString("") {
            when (it) {
                is MdInline.Text -> it.text
                is MdInline.Italic -> it.children.joinToString("") { c -> (c as? MdInline.Text)?.text.orEmpty() }
                else -> ""
            }
        }
        assertTrue("got: $joined", joined.contains("my_var_name"))
    }

    // ---- structure --------------------------------------------------------

    @Test
    fun `headings carry their level`() {
        val b = MarkdownParser.parse("## Section")
        val h = b[0] as MdBlock.Heading
        assertEquals(2, h.level)
    }

    @Test
    fun `bullet and numbered lists are recognised`() {
        val b = MarkdownParser.parse("- one\n- two\n1. first\n2. second")
        assertEquals(2, b.count { it is MdBlock.Bullet })
        assertEquals(2, b.count { it is MdBlock.Numbered })
        // Order is preserved and not interleaved into one blob.
        assertTrue(b[0] is MdBlock.Bullet)
        assertTrue(b[2] is MdBlock.Numbered)
    }

    @Test
    fun `nested bullets record their indent`() {
        val b = MarkdownParser.parse("- top\n    - nested")
        val nested = b.filterIsInstance<MdBlock.Bullet>()[1]
        assertTrue("indent was ${nested.indent}", nested.indent > 0)
    }

    @Test
    fun `a horizontal rule is not a bullet`() {
        val b = MarkdownParser.parse("---")
        assertTrue(b[0] is MdBlock.Rule)
    }

    @Test
    fun `a blockquote loses its marker`() {
        val q = MarkdownParser.parse("> quoted")[0] as MdBlock.Quote
        val text = q.inlines.filterIsInstance<MdInline.Text>().joinToString("") { it.text }
        assertEquals("quoted", text.trim())
    }

    @Test
    fun `a paragraph spanning several lines is one block`() {
        val b = MarkdownParser.parse("line one\nline two\nline three")
        assertEquals(1, b.size)
        assertTrue(b[0] is MdBlock.Paragraph)
    }

    @Test
    fun `empty input produces no blocks`() {
        assertTrue(MarkdownParser.parse("").isEmpty())
    }

    @Test
    fun `whitespace-only input produces no blocks`() {
        assertTrue(MarkdownParser.parse("   \n\n  ").isEmpty())
    }

    // ---- links ------------------------------------------------------------

    @Test
    fun `a markdown link keeps label and url`() {
        val link = MarkdownParser.parseInline("see [docs](https://x.dev)")
            .filterIsInstance<MdInline.Link>().first()
        assertEquals("https://x.dev", link.url)
    }

    @Test
    fun `an unclosed bracket stays literal`() {
        val text = MarkdownParser.parseInline("see [docs now")
            .filterIsInstance<MdInline.Text>().joinToString("") { it.text }
        assertTrue("got: $text", text.contains("["))
    }

    // ---- escapes ----------------------------------------------------------

    @Test
    fun `a backslash escapes the next character`() {
        val text = MarkdownParser.parseInline("""\*not bold\*""")
            .filterIsInstance<MdInline.Text>().joinToString("") { it.text }
        assertTrue("got: $text", text.contains("*not bold*"))
    }

    // ---- inline scan budget (recomposition-storm hardening) ----------------
    //
    // The budget charges cumulative distance for FAILED scans (a marker with
    // no closer makes every such scan walk to the end of the source). These
    // cases pin BOTH the bail-out itself and the guarantee that healthy input
    // is untouched. They assert behaviour, not wall clock alone - the
    // structural checks are load-bearing, the timing bound is a loud tripwire,
    // kept loose precisely so it never flakes on a slow CI worker.

    @Test
    fun `an unpaired bracket flood trips the scan budget and stays lossless`() {
        val n = 150_000
        // `]` before any `[`: no bracket ever finds a closer after itself, so
        // before the budget this parsed in seconds-to-minutes of indexOf
        // walking; with the budget it stops after ~64KB walked.
        val src = "]".repeat(n) + "[".repeat(n)
        val started = System.nanoTime()
        val out = MarkdownParser.parseInline(src)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("parse took ${elapsedMs}ms", elapsedMs < 10_000)
        // Lossless: every character here is a literal, so the inline runs must
        // reproduce the input exactly.
        val text = out.filterIsInstance<MdInline.Text>().joinToString("") { it.text }
        assertEquals(src, text)
    }

    @Test
    fun `after the budget trips the remainder renders as one plain run`() {
        val src = "]".repeat(20_000) + "[".repeat(40_000) + "TAIL-PLAIN-TAIL"
        val out = MarkdownParser.parseInline(src)
        // The bail-out gives up markup entirely: no Code/Bold/Italic/Link may
        // leak out of a throttled block.
        assertTrue("non-text inline escaped: $out", out.all { it is MdInline.Text })
        // The unread tail is emitted verbatim as ONE run at the end.
        val tail = out.filterIsInstance<MdInline.Text>().last().text
        assertTrue("tail marker missing in: $out", tail.contains("TAIL-PLAIN-TAIL"))
    }

    @Test
    fun `a long healthy line is not throttled by the scan budget`() {
        // One ~150KB paragraph whose every marker is well-formed: successful
        // scans consume only the text they span, so nothing is charged and the
        // budget must NOT fire - real formatting still applies end to end.
        val unit = "**bold** see [docs](https://x.dev) and `npm ci` ok "
        val out = MarkdownParser.parseInline(unit.repeat(3_000))
        assertTrue(out.any { it is MdInline.Bold })
        assertTrue(out.any { it is MdInline.Link })
        assertTrue(out.any { it is MdInline.Code })
        // Each unit still contributed its literal " and " - i.e. the parser
        // did not bail out partway and flush the rest as raw text.
        val plain = out.filterIsInstance<MdInline.Text>().joinToString("") { it.text }
        assertEquals(3_000, plain.split(" and ").size - 1)
    }
}
