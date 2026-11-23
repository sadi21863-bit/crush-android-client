package com.crush.mobile.ui.screens.chat

import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A12: the question payload is engine-versioned, so the parser must be
 * defensive. These tests pin the confirmed upstream schema
 * (`internal/question/question.go`), the `options` alias, and the refusal to
 * fabricate a choice when the payload is incomplete.
 */
class QuestionBatchParserTest {

    private fun parse(json: String): AskBatch? =
        QuestionBatchParser.parse(JsonParser.parseString(json))

    @Test
    fun `a full batch parses every question kind and its choices`() {
        val batch = parse(
            """
            {
              "id": "batch-1",
              "session_id": "s1",
              "tool_call_id": "t1",
              "confirm_title": "Ready?",
              "confirm_description": "Review your answers.",
              "questions": [
                {
                  "id": "q1",
                  "type": "single_choice",
                  "question": "Which file?",
                  "description": "Pick one",
                  "choices": [
                    {"id": "c1", "label": "A.kt", "description": "the model"},
                    {"id": "c2", "label": "B.kt"}
                  ]
                },
                {
                  "id": "q2",
                  "type": "multi_choice",
                  "question": "Which tests?",
                  "choices": [{"id": "t1", "label": "Unit"}, {"id": "t2", "label": "UI"}]
                },
                {"id": "q3", "type": "free_text", "question": "Why?"},
                {"id": "q4", "type": "yes_no", "question": "Continue?"}
              ]
            }
            """.trimIndent()
        )!!

        assertEquals("batch-1", batch.batchId)
        assertEquals("Ready?", batch.confirmTitle)
        assertEquals("Review your answers.", batch.confirmDescription)
        assertEquals(4, batch.prompts.size)

        val single = batch.prompts[0]
        assertEquals(AskKind.SINGLE_CHOICE, single.kind)
        assertEquals("Which file?", single.text)
        assertEquals("Pick one", single.description)
        assertEquals(listOf("c1", "c2"), single.choices.map { it.id })
        assertEquals("A.kt", single.choices[0].label)
        assertEquals("the model", single.choices[0].description)

        assertEquals(AskKind.MULTI_CHOICE, batch.prompts[1].kind)
        assertEquals(AskKind.FREE_TEXT, batch.prompts[2].kind)
        assertEquals(AskKind.YES_NO, batch.prompts[3].kind)
    }

    @Test
    fun `the legacy options alias is accepted`() {
        val batch = parse(
            """{"id":"b","questions":[{"id":"q","type":"single_choice",
               "question":"Pick","options":[{"id":"a","label":"A"},{"id":"b","label":"B"}]}]}"""
        )!!

        assertEquals(listOf("a", "b"), batch.prompts.single().choices.map { it.id })
    }

    @Test
    fun `a choice question with no choices is unanswerable and stays UNKNOWN`() {
        val batch = parse(
            """{"id":"b","questions":[{"id":"q","type":"single_choice","question":"Pick"}]}"""
        )!!

        assertEquals(AskKind.UNKNOWN, batch.prompts.single().kind)
        assertTrue(batch.prompts.single().choices.isEmpty())
    }

    @Test
    fun `an unknown type stays UNKNOWN rather than guessed`() {
        val batch = parse(
            """{"id":"b","questions":[{"id":"q","type":"ranking","question":"Order these"}]}"""
        )!!

        assertEquals(AskKind.UNKNOWN, batch.prompts.single().kind)
    }

    @Test
    fun `a missing batch id cannot be answered and returns null`() {
        assertNull(parse("""{"questions":[{"id":"q","type":"free_text","question":"Why?"}]}"""))
    }

    @Test
    fun `no questions returns null`() {
        assertNull(parse("""{"id":"b","questions":[]}"""))
        assertNull(parse("""{"id":"b"}"""))
        assertNull(QuestionBatchParser.parse(JsonPrimitive("not an object")))
        assertNull(QuestionBatchParser.parse(null))
    }

    @Test
    fun `a prompt without an id is skipped, and an all-skipped batch is null`() {
        val partial = parse(
            """{"id":"b","questions":[
                 {"type":"free_text","question":"no id"},
                 {"id":"q2","type":"free_text","question":"kept"}
               ]}"""
        )!!
        assertEquals(listOf("q2"), partial.prompts.map { it.id })

        assertNull(
            parse("""{"id":"b","questions":[{"type":"free_text","question":"no id"}]}""")
        )
    }

    @Test
    fun `the parser caps prompts and choices to the engine maxima`() {
        val questions = (1..8).joinToString(",") { qi ->
            val choices = (1..8).joinToString(",") { ci ->
                """{"id":"c$ci","label":"C$ci"}"""
            }
            """{"id":"q$qi","type":"multi_choice","question":"Q$qi","choices":[$choices]}"""
        }
        val batch = parse("""{"id":"b","questions":[$questions]}""")!!

        assertEquals(5, batch.prompts.size)
        assertTrue(batch.prompts.all { it.choices.size == 5 })
    }
}
