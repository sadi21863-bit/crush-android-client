package com.crush.mobile.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chat-side save surface's PURE contracts: exactly what gets written and
 * under what suggested name. (The SAF launcher itself is composition
 * machinery - its reply contract is device-proven on the Review screen's
 * identical shape, and the compose test runner would be re-testing the
 * activity-result library.)
 *
 * The load-bearing pins, in order of how badly they would betray the user:
 * 1. content = the FULL emitted block, never the display trim (a saved file
 *    that ends in "… (truncated)" would be a lie with our name on it);
 * 2. the suggested name is filename-safe (a fence tag is MODEL OUTPUT, so
 *    its shape is never trusted).
 */
class ChatExportTest {

    // ---- content: the full block, not the display-trimmed copy ----------

    @Test
    fun `code block content joins lines with newlines`() {
        val block = MdBlock.Code("python", listOf("def f():", "    return 1", ""))
        assertEquals("def f():\n    return 1\n", codeBlockContent(block))
    }

    @Test
    fun `content ignores the display max-lines trim`() {
        // A block LONGER than the layout cap - the display would append
        // "… (truncated)" after CODE_MAX_LINES lines; the saved content must
        // still carry every emitted line.
        val emitted = (1..CODE_MAX_LINES + 40).map { "line $it" }
        val block = MdBlock.Code("text", emitted)
        val content = codeBlockContent(block)
        assertEquals(emitted.size, content.split("\n").size)
        assertTrue(content.endsWith("\nline ${emitted.size}"))
        assertTrue(!content.contains("truncated"))
    }

    // ---- suggested names: mapped extensions ----------------------------

    @Test
    fun `common fence tags map to the expected extension`() {
        val rows = mapOf(
            "python" to "py", "py" to "py", "PYTHON3" to "py",
            "javascript" to "js", "node" to "js",
            "typescript" to "ts", "tsx" to "ts",
            "kotlin" to "kt", "java" to "java",
            "json" to "json", "yaml" to "yml",
            "bash" to "sh", "shell-session" to "sh",
            "markdown" to "md", "html" to "html", "css" to "css",
            "rust" to "rs", "golang" to "go", "C" to "c", "c++" to "cpp",
            "c#" to "cs", "swift" to "swift", "rb" to "rb",
            "php" to "php", "sql" to "sql", "xml" to "xml",
            "toml" to "toml", "diff" to "diff"
        )
        rows.forEach { (tag, ext) ->
            assertEquals("tag $tag", "code.$ext", codeSaveName(tag))
        }
    }

    @Test
    fun `missing or blank tag suggests a txt file`() {
        assertEquals("code.txt", codeSaveName(null))
        assertEquals("code.txt", codeSaveName(""))
        assertEquals("code.txt", codeSaveName("   "))
    }

    // ---- suggested names: the tag is model output, never trusted --------

    @Test
    fun `an unknown tag is sanitized into the suggested name`() {
        // Path-shaped tags collapse to filename-safe characters...
        assertEquals("code.textx-python", codeSaveName("text/x-python"))
        assertEquals("code.weirdlangwit", codeSaveName("weird lang with spaces"))
        //                ^-- the 12-char cap: "weirdlangwithspaces" is 19 chars;
        //                     take(12) lands mid-word and that is the DESIGN -
        //                     the name is a suggestion the picker lets you edit.
        // ...unsafe punctuation vanishes...
        assertEquals("code.junk", codeSaveName("JUNK!!"))
        // ...and a tag that sanitizes to nothing falls back to txt.
        assertEquals("code.txt", codeSaveName("///"))
        assertEquals("code.txt", codeSaveName("*/*/**"))
    }

    @Test
    fun `a sanitized rule-breaker tag cannot smuggle separators`() {
        // A fence tag is model output: whatever it contains, the suggested
        // name must stay ONE path segment with an extension-like shape.
        val name = codeSaveName("..%2F..%2Fetc")
        assertTrue(name.startsWith("code."))
        assertTrue(!name.contains('/'))
        assertTrue(!name.contains("\\"))
        assertTrue(!name.contains(".."))
    }

    // ---- message save name: timestamped, never silently overwriting ------

    @Test
    fun `message save name is message-HHmmss-md`() {
        assertTrue(messageSaveName().matches(Regex("message-\\d{6}\\.md")))
    }
}
