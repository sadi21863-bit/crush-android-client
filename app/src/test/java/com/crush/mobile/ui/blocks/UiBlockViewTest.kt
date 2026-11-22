package com.crush.mobile.ui.blocks

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.crush.mobile.ui.theme.CrushMobileTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * V2-7.3: the deterministic renderer.
 *
 * The document is fabricated from the public model rather than parsed, so a
 * parser regression cannot make this test pass vacuously.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class UiBlockViewTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setContent(vararg blocks: UiBlock) {
        compose.setContent {
            CrushMobileTheme(themeMode = "light") {
                UiBlockView(UiBlockDocument(blocks.toList()))
            }
        }
    }

    @Test
    fun `a table renders its headers and cells`() {
        setContent(
            UiBlock.Table(
                columns = listOf("Name", "Value"),
                rows = listOf(listOf("alpha", "1"), listOf("beta", "2")),
            )
        )

        compose.onNodeWithTag("ui-block-table").assertIsDisplayed()
        compose.onNodeWithText("Name").assertIsDisplayed()
        compose.onNodeWithText("alpha").assertIsDisplayed()
        compose.onNodeWithText("beta").assertIsDisplayed()
    }

    @Test
    fun `key-value pairs show both sides`() {
        setContent(
            UiBlock.KeyValue(
                listOf(
                    UiBlock.KeyValue.Item("Model", "DeepSeek V4.1 Flash"),
                    UiBlock.KeyValue.Item("Provider", "opencode-go"),
                )
            )
        )

        compose.onNodeWithTag("ui-block-kv").assertIsDisplayed()
        compose.onNodeWithText("Model").assertIsDisplayed()
        compose.onNodeWithText("DeepSeek V4.1 Flash").assertIsDisplayed()
    }

    @Test
    fun `a callout shows its title and body`() {
        setContent(
            UiBlock.Callout(
                tone = UiBlock.Callout.Tone.WARNING,
                title = "Careful",
                text = "This is a warning.",
            )
        )

        compose.onNodeWithTag("ui-block-callout").assertIsDisplayed()
        compose.onNodeWithText("Careful").assertIsDisplayed()
        compose.onNodeWithText("This is a warning.").assertIsDisplayed()
    }

    @Test
    fun `a code block shows the language and code`() {
        setContent(UiBlock.Code(language = "kotlin", text = "val answer = 42"))

        compose.onNodeWithTag("ui-block-code").assertIsDisplayed()
        compose.onNodeWithText("kotlin").assertIsDisplayed()
        compose.onNodeWithText("val answer = 42").assertIsDisplayed()
    }

    private fun sampleDocument(): UiBlockDocument = UiBlockDocument(
        listOf(
            UiBlock.Table(
                columns = listOf("Name", "Value"),
                rows = listOf(listOf("alpha", "1")),
            ),
            UiBlock.KeyValue(
                listOf(UiBlock.KeyValue.Item("Model", "DeepSeek V4.1 Flash"))
            ),
            UiBlock.Callout(
                tone = UiBlock.Callout.Tone.WARNING,
                title = "Careful",
                text = "This is a warning.",
            ),
        )
    )

    @Test
    fun `a document renders in the dark theme`() {
        compose.setContent {
            CrushMobileTheme(themeMode = "dark") {
                UiBlockView(sampleDocument())
            }
        }

        compose.onNodeWithText("alpha").assertExists()
        compose.onNodeWithText("DeepSeek V4.1 Flash").assertExists()
        compose.onNodeWithText("Careful").assertExists()
    }

    @Test
    fun `a document renders at a 2x font scale`() {
        compose.setContent {
            CrushMobileTheme(themeMode = "light") {
                val base = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(base.density, fontScale = 2f)
                ) {
                    UiBlockView(sampleDocument())
                }
            }
        }

        compose.onNodeWithText("alpha").assertExists()
        compose.onNodeWithText("DeepSeek V4.1 Flash").assertExists()
    }

    @Test
    fun `a document renders right-to-left`() {
        compose.setContent {
            CrushMobileTheme(themeMode = "light") {
                CompositionLocalProvider(
                    LocalLayoutDirection provides LayoutDirection.Rtl
                ) {
                    UiBlockView(sampleDocument())
                }
            }
        }

        compose.onNodeWithText("alpha").assertExists()
        compose.onNodeWithText("Careful").assertExists()
    }
}
