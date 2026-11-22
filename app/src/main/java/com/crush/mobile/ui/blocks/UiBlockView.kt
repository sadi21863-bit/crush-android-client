package com.crush.mobile.ui.blocks

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.crush.mobile.ui.theme.CrushSpacing
import com.crush.mobile.ui.theme.CrushType

/**
 * Deterministic renderer for a validated [UiBlockDocument].
 *
 * No interaction, no URLs, no images, no network. Every block maps to a fixed
 * Compose structure; the same document renders the same tree on every pass,
 * which is what makes it safe to feed from a model later (V2-8) without turning
 * the transcript into a canvas.
 */
@Composable
fun UiBlockView(document: UiBlockDocument, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(CrushSpacing.sm)
    ) {
        document.blocks.forEach { block ->
            when (block) {
                is UiBlock.Text -> Text(
                    block.text,
                    style = CrushType.body,
                    color = MaterialTheme.colorScheme.onSurface
                )
                is UiBlock.Code -> CodeBlock(block)
                is UiBlock.Table -> TableBlock(block)
                is UiBlock.KeyValue -> KeyValueBlock(block)
                is UiBlock.Callout -> CalloutBlock(block)
                UiBlock.Divider -> HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }
        }
    }
}

@Composable
private fun CodeBlock(block: UiBlock.Code) {
    Column(modifier = Modifier.fillMaxWidth().testTag("ui-block-code")) {
        if (block.language.isNotBlank()) {
            Text(
                block.language,
                style = CrushType.metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                .padding(8.dp)
        ) {
            Text(
                block.text,
                style = CrushType.code,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.horizontalScroll(rememberScrollState())
            )
        }
    }
}

@Composable
private fun TableBlock(block: UiBlock.Table) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .testTag("ui-block-table")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
        ) {
            block.columns.forEach { column ->
                Text(
                    column,
                    modifier = Modifier.weight(1f).padding(6.dp),
                    style = CrushType.metadata,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        block.rows.forEach { row ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { cell ->
                    Text(
                        cell,
                        modifier = Modifier.weight(1f).padding(6.dp),
                        style = CrushType.secondary,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun KeyValueBlock(block: UiBlock.KeyValue) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag("ui-block-kv"),
        verticalArrangement = Arrangement.spacedBy(CrushSpacing.xs)
    ) {
        block.items.forEach { item ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    item.key,
                    modifier = Modifier.weight(0.4f),
                    style = CrushType.metadata,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    item.value,
                    modifier = Modifier.weight(0.6f),
                    style = CrushType.body,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun CalloutBlock(block: UiBlock.Callout) {
    val scheme = MaterialTheme.colorScheme
    val container = when (block.tone) {
        UiBlock.Callout.Tone.INFO -> scheme.secondaryContainer
        UiBlock.Callout.Tone.WARNING -> scheme.tertiaryContainer
        UiBlock.Callout.Tone.ERROR -> scheme.errorContainer
    }
    val content = when (block.tone) {
        UiBlock.Callout.Tone.INFO -> scheme.onSecondaryContainer
        UiBlock.Callout.Tone.WARNING -> scheme.onTertiaryContainer
        UiBlock.Callout.Tone.ERROR -> scheme.onErrorContainer
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(container)
            .padding(10.dp)
            .testTag("ui-block-callout")
    ) {
        if (block.title.isNotBlank()) {
            Text(block.title, style = CrushType.metadata, color = content)
        }
        Text(block.text, style = CrushType.body, color = content)
    }
}
