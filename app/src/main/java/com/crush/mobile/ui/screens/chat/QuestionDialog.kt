package com.crush.mobile.ui.screens.chat

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.crush.mobile.ui.theme.CrushSpacing
import com.crush.mobile.ui.theme.CrushType

/**
 * A12: renders the parsed question batch and submits real answers.
 *
 * Only called when [QuestionView.batch] is non-null. The answer body is built
 * solely from ids the engine published; no option is invented. An UNKNOWN
 * prompt (unparsable type or a choice question with no choices) disables
 * Submit, leaving Cancel as the only way to release the blocked run.
 *
 * Dismiss/back is Cancel, never Submit: a swipe must not send a partial batch.
 */
@Composable
internal fun AnswerQuestionDialog(
    view: QuestionView,
    error: String?,
    onCancel: () -> Unit,
    onSubmit: (List<AskAnswerDraft>) -> Unit,
) {
    val batch = view.batch ?: return
    var selections by remember(view.id) {
        mutableStateOf<Map<String, Set<String>>>(emptyMap())
    }
    var fills by remember(view.id) {
        mutableStateOf<Map<String, String>>(emptyMap())
    }
    var yes by remember(view.id) {
        mutableStateOf<Map<String, Boolean>>(emptyMap())
    }

    fun draftFor(prompt: AskPrompt): AskAnswerDraft? = when (prompt.kind) {
        AskKind.YES_NO -> yes[prompt.id]?.let { AskAnswerDraft(prompt.id, yes = it) }
        AskKind.SINGLE_CHOICE -> selections[prompt.id]
            ?.takeIf { it.size == 1 }
            ?.let { AskAnswerDraft(prompt.id, selectedIds = it.toList()) }
        AskKind.MULTI_CHOICE -> selections[prompt.id]
            ?.takeIf { it.isNotEmpty() }
            ?.let { AskAnswerDraft(prompt.id, selectedIds = it.toList()) }
        AskKind.FREE_TEXT -> fills[prompt.id]
            ?.takeIf { it.isNotBlank() }
            ?.let { AskAnswerDraft(prompt.id, fillInText = it.trim()) }
        AskKind.UNKNOWN -> null
    }

    val ready = batch.prompts.all { draftFor(it) != null }

    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                batch.confirmTitle
                    ?: view.title.ifBlank { "The agent has a question" }
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                batch.confirmDescription?.let {
                    Text(
                        it,
                        style = CrushType.secondary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(CrushSpacing.sm))
                }
                batch.prompts.forEachIndexed { index, prompt ->
                    if (index > 0) Spacer(Modifier.height(CrushSpacing.md))
                    Text(prompt.text, style = CrushType.section)
                    prompt.description?.let {
                        Text(
                            it,
                            style = CrushType.secondary,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(CrushSpacing.xs))
                    when (prompt.kind) {
                        AskKind.YES_NO -> {
                            ChoiceRow(
                                label = "Yes",
                                selected = yes[prompt.id] == true,
                                multi = false,
                                tag = "question-yes-${prompt.id}",
                                onClick = { yes = yes + (prompt.id to true) }
                            )
                            ChoiceRow(
                                label = "No",
                                selected = yes[prompt.id] == false,
                                multi = false,
                                tag = "question-no-${prompt.id}",
                                onClick = { yes = yes + (prompt.id to false) }
                            )
                        }
                        AskKind.SINGLE_CHOICE -> prompt.choices.forEach { choice ->
                            ChoiceRow(
                                label = choice.label,
                                description = choice.description,
                                selected = selections[prompt.id]?.contains(choice.id) == true,
                                multi = false,
                                tag = "question-choice-${choice.id}",
                                onClick = {
                                    selections = selections + (prompt.id to setOf(choice.id))
                                }
                            )
                        }
                        AskKind.MULTI_CHOICE -> prompt.choices.forEach { choice ->
                            val current = selections[prompt.id].orEmpty()
                            ChoiceRow(
                                label = choice.label,
                                description = choice.description,
                                selected = current.contains(choice.id),
                                multi = true,
                                tag = "question-choice-${choice.id}",
                                onClick = {
                                    selections = selections + (
                                        prompt.id to
                                            if (current.contains(choice.id)) {
                                                current - choice.id
                                            } else {
                                                current + choice.id
                                            }
                                        )
                                }
                            )
                        }
                        AskKind.FREE_TEXT -> BasicTextField(
                            value = fills[prompt.id].orEmpty(),
                            onValueChange = { fills = fills + (prompt.id to it) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("question-free-text-${prompt.id}"),
                            textStyle = CrushType.body.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            decorationBox = { inner ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(
                                            width = 1.dp,
                                            color = MaterialTheme.colorScheme.outline,
                                            shape = RectangleShape,
                                        )
                                        .padding(CrushSpacing.sm)
                                ) {
                                    if (fills[prompt.id].isNullOrEmpty()) {
                                        Text(
                                            "Your answer",
                                            style = CrushType.secondary,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    inner()
                                }
                            },
                        )
                        AskKind.UNKNOWN -> Text(
                            "This question type is not supported by this build. " +
                                "Cancel to release the run.",
                            style = CrushType.secondary,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                error?.let {
                    Spacer(Modifier.height(CrushSpacing.sm))
                    Text(
                        it,
                        style = CrushType.secondary,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = ready,
                onClick = {
                    onSubmit(batch.prompts.mapNotNull { draftFor(it) })
                },
                modifier = Modifier.testTag("question-submit")
            ) { Text("Submit") }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.testTag("question-cancel")
            ) { Text("Cancel") }
        },
    )
}

@Composable
private fun ChoiceRow(
    label: String,
    selected: Boolean,
    multi: Boolean,
    tag: String,
    onClick: () -> Unit,
    description: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (multi) {
            Checkbox(checked = selected, onCheckedChange = { onClick() })
        } else {
            RadioButton(selected = selected, onClick = { onClick() })
        }
        Spacer(Modifier.width(CrushSpacing.xs))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = CrushType.body,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            description?.let {
                Text(
                    it,
                    style = CrushType.secondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
