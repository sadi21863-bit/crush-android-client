package com.crush.mobile.ui.screens.spike

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.crush.mobile.ui.blocks.UiBlock
import com.crush.mobile.ui.blocks.UiBlockDocument
import com.crush.mobile.ui.blocks.UiBlockView
import com.crush.mobile.ui.theme.CrushMobileTheme
import com.crush.mobile.ui.theme.CrushSpacing

/**
 * V2-7.3: debug-only preview of the `ui_block/v1` renderer.
 *
 * There is no production producer yet - model-authored blocks are V2-8. This
 * activity renders a host-authored document through the SAME composable the
 * experiment will use, so the renderer can be inspected on a real device
 * without wiring it into the transcript.
 *
 * Debug source set only; the manifest entry is exported because this ROM's shell
 * refuses `am start` on non-exported activities. The screen is static and takes
 * no input:
 *
 *     adb shell am start -n com.crush.mobile/.ui.screens.spike.UiBlockPreviewActivity
 */
@OptIn(ExperimentalMaterial3Api::class)
class UiBlockPreviewActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CrushMobileTheme(themeMode = "light") {
                Scaffold(
                    topBar = { TopAppBar(title = { Text("ui_block/v1 preview") }) }
                ) { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = CrushSpacing.lg, vertical = CrushSpacing.md)
                    ) {
                        UiBlockView(sampleDocument())
                    }
                }
            }
        }
    }

    private fun sampleDocument(): UiBlockDocument = UiBlockDocument(
        listOf(
            UiBlock.Text("Turn summary, rendered by the host. No HTML, no actions."),
            UiBlock.KeyValue(
                listOf(
                    UiBlock.KeyValue.Item("Model", "DeepSeek V4.1 Flash"),
                    UiBlock.KeyValue.Item("Provider", "opencode-go"),
                    UiBlock.KeyValue.Item("Tokens", "1,204"),
                )
            ),
            UiBlock.Table(
                columns = listOf("File", "Change"),
                rows = listOf(
                    listOf("MainActivity.kt", "+12 -3"),
                    listOf("ChatContent.kt", "+48 -2"),
                    listOf("UiBlock.kt", "new"),
                )
            ),
            UiBlock.Callout(
                tone = UiBlock.Callout.Tone.WARNING,
                title = "Read-only preview",
                text = "This document is host-authored. A model is not producing blocks in this build.",
            ),
            UiBlock.Code(
                language = "kotlin",
                text = "val answer = 42\nprintln(answer)"
            ),
            UiBlock.Divider,
            UiBlock.Text("End of document.")
        )
    )
}
