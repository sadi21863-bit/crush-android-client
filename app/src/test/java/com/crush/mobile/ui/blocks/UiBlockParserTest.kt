package com.crush.mobile.ui.blocks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V2-7.3: the closed block validator.
 *
 * These are JVM tests on purpose: parsing must not depend on Compose or Android.
 * Every rejection is whole-document, so the caller can fall back to the original
 * text without rendering a partial tree.
 */
class UiBlockParserTest {

    private fun doc(blocks: String) = """{"v":"ui_block/v1","blocks":[$blocks]}"""

    @Test
    fun `a mixed valid document parses every block type`() {
        val parsed = UiBlockParser.parse(
            doc(
                """
                {"type":"text","text":"Summary"},
                {"type":"code","lang":"kotlin","text":"val x = 1"},
                {"type":"table","columns":["Name","Value"],"rows":[["a","1"],["b","2"]]},
                {"type":"kv","items":[{"k":"Model","v":"DeepSeek"}]},
                {"type":"callout","tone":"warning","title":"Careful","text":"Check this"},
                {"type":"divider"}
                """.trimIndent()
            )
        )
        val document = (parsed as UiBlockParse.Valid).document
        assertEquals(6, document.blocks.size)
        assertTrue(document.blocks[0] is UiBlock.Text)
        assertEquals("kotlin", (document.blocks[1] as UiBlock.Code).language)
        assertEquals(2, (document.blocks[2] as UiBlock.Table).rows.size)
        assertEquals(1, (document.blocks[3] as UiBlock.KeyValue).items.size)
        assertEquals(
            UiBlock.Callout.Tone.WARNING,
            (document.blocks[4] as UiBlock.Callout).tone
        )
        assertEquals(UiBlock.Divider, document.blocks[5])
    }

    @Test
    fun `a wrong version is rejected`() {
        val reason = reasonOf("""{"v":"ui_block/v2","blocks":[{"type":"text","text":"x"}]}""")
        assertTrue(reason.contains("version"))
    }

    @Test
    fun `an unknown block type is rejected`() {
        val reason = reasonOf(doc("""{"type":"chart","data":[]}"""))
        assertTrue(reason.contains("unknown type"))
    }

    @Test
    fun `an unknown field is rejected even when the rest is valid`() {
        val reason = reasonOf(doc("""{"type":"text","text":"x","onClick":"evil"}"""))
        assertTrue(reason.contains("unexpected field"))
    }

    @Test
    fun `an unknown root field is rejected`() {
        val reason = reasonOf(
            """{"v":"ui_block/v1","blocks":[{"type":"text","text":"x"}],"url":"https://x"}"""
        )
        assertTrue(reason.contains("unexpected field"))
    }

    @Test
    fun `a table row with the wrong cell count is rejected`() {
        val reason = reasonOf(
            doc("""{"type":"table","columns":["a","b"],"rows":[["1"]]}""")
        )
        assertTrue(reason.contains("cell"))
    }

    @Test
    fun `too many table columns is rejected`() {
        val columns = (1..9).joinToString(",") { "\"c$it\"" }
        val reason = reasonOf(doc("""{"type":"table","columns":[$columns],"rows":[]}"""))
        assertTrue(reason.contains("columns"))
    }

    @Test
    fun `a bad callout tone is rejected`() {
        val reason = reasonOf(
            doc("""{"type":"callout","tone":"success","text":"x"}""")
        )
        assertTrue(reason.contains("tone"))
    }

    @Test
    fun `a key-value item without a key is rejected`() {
        val reason = reasonOf(doc("""{"type":"kv","items":[{"v":"x"}]}"""))
        assertTrue(reason.contains("key"))
    }

    @Test
    fun `a single text block works`() {
        val document = (UiBlockParser.parse(doc("""{"type":"text","text":"hello"}"""))
            as UiBlockParse.Valid).document
        assertEquals("hello", (document.blocks.single() as UiBlock.Text).text)
    }

    @Test
    fun `malformed JSON is rejected without throwing`() {
        val reason = reasonOf("{not json")
        assertTrue(reason.contains("JSON"))
    }

    @Test
    fun `a source over the cap is rejected`() {
        val huge = doc("""{"type":"text","text":"${"x".repeat(17_000)}"}""")
        val reason = reasonOf(huge)
        assertTrue(reason.contains("chars"))
    }

    private fun reasonOf(json: String): String =
        (UiBlockParser.parse(json) as UiBlockParse.Invalid).reason
}
