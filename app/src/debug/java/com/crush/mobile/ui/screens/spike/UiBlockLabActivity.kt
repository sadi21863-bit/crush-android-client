package com.crush.mobile.ui.screens.spike

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.crush.mobile.ui.blocks.UiBlockParse
import com.crush.mobile.ui.blocks.UiBlockParser
import com.crush.mobile.ui.blocks.UiBlockView
import com.crush.mobile.ui.blocks.normalizeUiBlockPayload
import com.crush.mobile.ui.theme.CrushMobileTheme
import com.crush.mobile.ui.theme.CrushSpacing
import com.crush.mobile.ui.theme.CrushType

/**
 * V2-8 candidate A: a debug-only lab for model-produced `ui_block/v1`.
 *
 * The production app has no per-turn structured-output request, so the producer
 * step is deliberately manual: ask a model for the envelope in Chat, copy the
 * reply, paste it here. This activity strips at most one outer Markdown fence,
 * validates strictly, and renders through the same `UiBlockView` as V2-7.3.
 * Nothing here is reachable from a release build; it is exported only because
 * this ROM's shell cannot start a non-exported activity.
 *
 *     adb shell am start -n com.crush.mobile/.ui.screens.spike.UiBlockLabActivity
 */
@OptIn(ExperimentalMaterial3Api::class)
class UiBlockLabActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CrushMobileTheme(themeMode = "light") {
                LabScreen()
            }
        }
    }
}

private const val EXPERIMENT_PROMPT =
    "Return ONLY one JSON object, no prose and no markdown fence. " +
        "It must match ui_block/v1: {\"v\":\"ui_block/v1\",\"blocks\":[...]}. " +
        "Allowed block types: text, code, table, kv, callout, divider. " +
        "Summarize a commit touching MainActivity.kt (+12 -3), " +
        "ChatContent.kt (+48 -2) and UiBlock.kt (new). " +
        "Include a table of the files, a kv block with Model, Provider and " +
        "Tokens, and a warning callout."

private val SAMPLE_PAYLOAD = """
    {"v":"ui_block/v1","blocks":[
      {"type":"text","text":"Commit summary"},
      {"type":"table","columns":["File","Change"],"rows":[
        ["MainActivity.kt","+12 -3"],
        ["ChatContent.kt","+48 -2"],
        ["UiBlock.kt","new"]]},
      {"type":"kv","items":[
        {"k":"Model","v":"DeepSeek V4.1 Flash"},
        {"k":"Provider","v":"opencode-go"},
        {"k":"Tokens","v":"1,204"}]},
      {"type":"callout","tone":"warning","title":"Read-only",
       "text":"Parsed from pasted model output."}
    ]}
""".trimIndent()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LabScreen() {
    var payload by remember { mutableStateOf(TextFieldValue(SAMPLE_PAYLOAD)) }
    var result by remember { mutableStateOf<UiBlockParse?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("ui_block lab") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = CrushSpacing.lg, vertical = CrushSpacing.md)
        ) {
            Text("Experiment prompt", style = CrushType.metadata)
            Spacer(Modifier.height(CrushSpacing.xs))
            SelectionContainer {
                Text(
                    EXPERIMENT_PROMPT,
                    style = CrushType.secondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(CrushSpacing.md))

            OutlinedTextField(
                value = payload,
                onValueChange = {
                    payload = it
                    result = null
                },
                label = { Text("Paste model output") },
                minLines = 8,
                maxLines = 14,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(CrushSpacing.sm))

            Row {
                Button(
                    onClick = {
                        result = UiBlockParser.parse(
                            normalizeUiBlockPayload(payload.text)
                        )
                    }
                ) {
                    Text("Parse and render")
                }
                Spacer(Modifier.width(CrushSpacing.sm))
                OutlinedButton(
                    onClick = {
                        payload = TextFieldValue(SAMPLE_PAYLOAD)
                        result = null
                    }
                ) {
                    Text("Reset sample")
                }
            }
            Spacer(Modifier.height(CrushSpacing.md))

            when (val outcome = result) {
                null -> Text(
                    "Nothing parsed yet.",
                    style = CrushType.secondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                is UiBlockParse.Invalid -> Text(
                    "Rejected: ${outcome.reason}",
                    style = CrushType.body,
                    color = MaterialTheme.colorScheme.error
                )
                is UiBlockParse.Valid -> {
                    Text(
                        "Parsed ${outcome.document.blocks.size} block(s)",
                        style = CrushType.metadata,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(CrushSpacing.sm))
                    UiBlockView(outcome.document)
                }
            }
            Spacer(Modifier.height(CrushSpacing.lg))
        }
    }
}
