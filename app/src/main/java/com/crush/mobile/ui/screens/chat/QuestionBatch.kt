package com.crush.mobile.ui.screens.chat

import com.crush.mobile.data.crush.QuestionAnswer
import com.crush.mobile.data.crush.QuestionResponse
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/**
 * A12: the engine's question batch, parsed defensively from the raw
 * `question_batch_request` payload.
 *
 * Upstream schema (charmbracelet/crush `internal/question`):
 * `{ id, session_id, tool_call_id, questions: [{ id, type, label, question,
 * description, choices: [{ id, label, description }] }], confirm_title,
 * confirm_description }`.
 *
 * Types: `yes_no`, `single_choice`, `multi_choice`, `free_text`. The parser
 * accepts `options` as a legacy alias for `choices` and never invents a
 * choice: an unparsable prompt is [AskKind.UNKNOWN] and the UI can only cancel
 * that batch, which is the pre-A12 behavior.
 */
enum class AskKind { YES_NO, SINGLE_CHOICE, MULTI_CHOICE, FREE_TEXT, UNKNOWN }

data class AskChoice(
    val id: String,
    val label: String,
    val description: String? = null,
)

data class AskPrompt(
    val id: String,
    val kind: AskKind,
    val text: String,
    val description: String? = null,
    val choices: List<AskChoice> = emptyList(),
)

data class AskBatch(
    val batchId: String,
    val prompts: List<AskPrompt>,
    val confirmTitle: String? = null,
    val confirmDescription: String? = null,
)

/** One prompt's answer, before it becomes a wire [com.crush.mobile.data.crush.QuestionResponse]. */
data class AskAnswerDraft(
    val promptId: String,
    val selectedIds: List<String> = emptyList(),
    val fillInText: String? = null,
    val yes: Boolean? = null,
)

object QuestionBatchParser {
    private const val MAX_PROMPTS = 5
    private const val MAX_CHOICES = 5

    private val TEXT_KEYS = listOf("question", "text", "label", "title", "message", "prompt")
    private val ID_KEYS = listOf("id", "request_id")
    private val BATCH_ID_KEYS = listOf("id", "request_id", "batch_id")

    fun parse(raw: JsonElement?): AskBatch? {
        val obj = raw as? JsonObject ?: return null
        val batchId = obj.firstText(BATCH_ID_KEYS) ?: return null
        val questions = obj.get("questions") as? JsonArray ?: return null
        if (questions.size() == 0) return null

        val prompts = questions.take(MAX_PROMPTS).mapNotNull { element ->
            val q = element as? JsonObject ?: return@mapNotNull null
            val id = q.firstText(ID_KEYS) ?: return@mapNotNull null
            val kind = when (q.firstText(listOf("type"))?.lowercase()) {
                "yes_no" -> AskKind.YES_NO
                "single_choice" -> AskKind.SINGLE_CHOICE
                "multi_choice" -> AskKind.MULTI_CHOICE
                "free_text" -> AskKind.FREE_TEXT
                else -> AskKind.UNKNOWN
            }
            val text = q.firstText(TEXT_KEYS)?.take(240) ?: "Question"
            val description = q.firstText(listOf("description"))?.take(600)
            val choices = parseChoices(q)
            // A choice question with no choices is not answerable; keep it
            // visible but UNKNOWN so the UI cannot submit a guessed id.
            val effectiveKind = if (
                (kind == AskKind.SINGLE_CHOICE || kind == AskKind.MULTI_CHOICE) &&
                choices.isEmpty()
            ) AskKind.UNKNOWN else kind
            AskPrompt(id, effectiveKind, text, description, choices)
        }
        if (prompts.isEmpty()) return null

        return AskBatch(
            batchId = batchId,
            prompts = prompts,
            confirmTitle = obj.firstText(listOf("confirm_title"))?.take(240),
            confirmDescription = obj.firstText(listOf("confirm_description"))?.take(600),
        )
    }

    private fun parseChoices(q: JsonObject): List<AskChoice> {
        val array = (q.get("choices") ?: q.get("options")) as? JsonArray ?: return emptyList()
        return array.take(MAX_CHOICES).mapNotNull { element ->
            val c = element as? JsonObject ?: return@mapNotNull null
            val id = c.firstText(listOf("id", "value")) ?: return@mapNotNull null
            val label = c.firstText(listOf("label", "title", "text", "value")) ?: id
            AskChoice(
                id = id,
                label = label.take(200),
                description = c.firstText(listOf("description"))?.take(200),
            )
        }
    }

    private fun JsonObject.firstText(keys: List<String>): String? =
        keys.firstNotNullOfOrNull { key ->
            val value = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.asString
            value?.takeIf { it.isNotBlank() }
        }
}

/**
 * A12: the exact wire body for `POST /questions/answer`.
 *
 * Pure and separated from the ViewModel so the mapping (including the
 * omission of blank/empty fields) is pinned without Android. `yes = false` is
 * a real answer and must be serialized; `null` means "not a yes/no prompt".
 */
object QuestionAnswerBuilder {
    fun build(batchId: String, answers: List<AskAnswerDraft>): QuestionAnswer =
        QuestionAnswer(
            batchRequestId = batchId,
            responses = answers.map { answer ->
                QuestionResponse(
                    questionId = answer.promptId,
                    selectedIds = answer.selectedIds.ifEmpty { null },
                    fillInText = answer.fillInText?.trim()?.takeIf { it.isNotBlank() },
                    yes = answer.yes,
                )
            },
        )
}
