package com.opencode.chat.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Renders assistant text as Markdown.
 *
 * WHY HAND-ROLLED
 *
 * No Markdown dependency. The alternative is a library that is either
 * unmaintained or a permanent supply-chain surface, for the subset of the spec
 * that a chat assistant actually emits. This is not CommonMark and does not
 * pretend to be; see [MarkdownParser].
 *
 * CODE IS THE POINT
 *
 * For a coding agent, fenced code is most of the value, so code blocks get a
 * language label, a monospaced surface distinct from prose, preserved
 * indentation, and their own horizontal scroll. Long lines wrap badly inside a
 * chat bubble, so they scroll instead - wrapping code changes its meaning.
 */
@Composable
fun MarkdownText(
    src: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    // Re-parsed only when the text changes. Streaming re-parses on every frame,
    // so caching matters once the reply gets long.
    val blocks = remember(src) { MarkdownParser.parse(src) }

    Column(modifier = modifier) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) Spacer(Modifier.height(4.dp))
            when (block) {
                is MdBlock.Code -> CodeBlock(block)
                is MdBlock.Heading -> Text(
                    inlineAnnotated(block.inlines, color, block.level),
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                is MdBlock.Paragraph -> Text(
                    inlineAnnotated(block.inlines, color),
                    style = MaterialTheme.typography.bodyMedium
                )
                is MdBlock.Bullet -> Row(Modifier.padding(start = (block.indent * 12).dp)) {
                    Text(
                        "•",
                        style = MaterialTheme.typography.bodyMedium,
                        color = color,
                        modifier = Modifier.width(14.dp)
                    )
                    Text(
                        inlineAnnotated(block.inlines, color),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                is MdBlock.Numbered -> Row(Modifier.padding(start = (block.indent * 12).dp)) {
                    Text(
                        "${block.number}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = color,
                        modifier = Modifier.width(20.dp)
                    )
                    Text(
                        inlineAnnotated(block.inlines, color),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                is MdBlock.Quote -> Row {
                    Text(
                        "▏",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        inlineAnnotated(block.inlines, color),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 6.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                is MdBlock.Rule -> HorizontalDivider(
                    modifier = Modifier.padding(vertical = 6.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }
        }
    }
}

@Composable
private fun CodeBlock(block: MdBlock.Code) {
    val bg = MaterialTheme.colorScheme.surfaceVariant
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        block.lang?.let { lang ->
            Text(
                lang,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(6.dp))
        }
        // Horizontal scroll, not wrap: re-flowed code is not the code that was
        // written, and on a narrow phone wrap makes it unreadable.
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            Text(
                text = block.lines.joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/** Flattens parsed inlines into an [AnnotatedString] with styles applied. */
internal fun inlineAnnotated(
    inlines: List<MdInline>,
    color: Color,
    headingLevel: Int = 0
): AnnotatedString = buildAnnotatedString {
    fun walk(items: List<MdInline>) {
        items.forEach { item ->
            when (item) {
                is MdInline.Text -> {
                    if (headingLevel > 0) {
                        pushStyle(SpanStyle(fontSize = (20 - headingLevel * 2).sp))
                    }
                    append(item.text)
                    if (headingLevel > 0) pop()
                }
                is MdInline.Code -> {
                    pushStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = color.copy(alpha = 0.10f)
                        )
                    )
                    append(item.text)
                    pop()
                }
                is MdInline.Bold -> {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    walk(item.children)
                    pop()
                }
                is MdInline.Italic -> {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    walk(item.children)
                    pop()
                }
                is MdInline.Link -> {
                    // Underlined rather than clickable for now: opening arbitrary
                    // URLs from a bubble needs a deliberate affordance and a
                    // confirmation, and that is a separate decision.
                    pushStyle(SpanStyle(textDecoration = TextDecoration.Underline))
                    walk(item.label)
                    pop()
                }
            }
        }
    }
    walk(inlines)
}
