package com.crush.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.crush.mobile.ui.theme.CrushDimens
import com.crush.mobile.ui.theme.CrushShape
import com.crush.mobile.ui.theme.CrushSpacing
import com.crush.mobile.ui.theme.CrushType
import com.crush.mobile.ui.theme.LocalCrushColors

/** Semantic tone for status text, banners and labels. Never colour-alone. */
enum class CrushTone { Neutral, Accent, Success, Warning, Error }

enum class CrushButtonVariant { Primary, Secondary, TextOnly }

@Composable
fun CrushSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = CrushType.section,
        color = LocalCrushColors.current.ink,
        modifier = modifier,
    )
}

/**
 * A status/provenance label: a tone dot plus real words. The words carry the
 * meaning; the dot is decorative, so removing colour loses nothing.
 */
@Composable
fun CrushStatusLabel(
    text: String,
    modifier: Modifier = Modifier,
    tone: CrushTone = CrushTone.Neutral,
) {
    val colors = LocalCrushColors.current
    val tint = when (tone) {
        CrushTone.Neutral -> colors.muted
        CrushTone.Accent -> colors.accent
        CrushTone.Success -> colors.success
        CrushTone.Warning -> colors.warning
        CrushTone.Error -> colors.error
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(tint, CircleShape)
        )
        Spacer(Modifier.width(CrushSpacing.sm))
        Text(
            text = text,
            style = CrushType.metadata,
            color = colors.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Flat card: a 1dp separation rule, never a shadow (brief 4.4/4.7). */
@Composable
fun CrushCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = CrushShape.standard,
        // Measured on device 2026-11-20 (light and dark): a fill-only card is
        // 1.02:1 against paper and reads as no container at all. The brief
        // asks for a hairline, not a shadow, so the boundary is explicit.
        border = BorderStroke(
            width = CrushDimens.hairline,
            color = LocalCrushColors.current.separator,
        ),
    ) {
        Column(
            modifier = Modifier.padding(CrushSpacing.lg),
            content = content,
        )
    }
}

/**
 * V2-4 F-J: a choice chip whose selected state survives the neutral accent.
 *
 * The Settings FilterChips differed from their siblings by a subtle fill only,
 * which V2-A8 predicted would become too weak once the violet accent was gone.
 * Selection here is a filled check + accent border + semi-bold label, while the
 * chip's own `selected` semantics still carries the state to assistive tech.
 */
@Composable
fun CrushChoiceChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCrushColors.current
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        label = {
            Text(
                text = text,
                style = CrushType.control,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        },
        leadingIcon = if (selected) {
            {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
        } else {
            null
        },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = colors.surface,
            labelColor = colors.ink,
            selectedContainerColor = colors.accentSoft,
            selectedLabelColor = colors.ink,
            selectedLeadingIconColor = colors.accent,
        ),
        border = BorderStroke(
            width = CrushDimens.hairline,
            color = if (selected) colors.accent else colors.separator,
        ),
    )
}

/**
 * A non-interactive chip. Deliberately has no click target: a chip that looks
 * tappable but does nothing is announced as a fake action.
 */
@Composable
fun CrushChip(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    val colors = LocalCrushColors.current
    Surface(
        modifier = modifier,
        shape = CrushShape.small,
        color = if (selected) colors.accentSoft else colors.surfaceVariant,
        border = BorderStroke(
            width = CrushDimens.hairline,
            color = if (selected) colors.accent else colors.separator,
        ),
    ) {
        Text(
            text = text,
            style = CrushType.metadata,
            color = colors.ink,
            modifier = Modifier.padding(horizontal = CrushSpacing.sm, vertical = CrushSpacing.xs),
        )
    }
}

@Composable
fun CrushEmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = LocalCrushColors.current
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CrushSpacing.sm),
    ) {
        Text(
            text = title,
            style = CrushType.section,
            color = colors.ink,
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            style = CrushType.body,
            color = colors.muted,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(CrushSpacing.sm))
            CrushButton(
                text = actionLabel,
                onClick = onAction,
                variant = CrushButtonVariant.Secondary,
            )
        }
    }
}

/** A loading row announces once; it never animates text that is being read. */
@Composable
fun CrushLoadingRow(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCrushColors.current
    Row(
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CrushSpacing.md),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp,
            color = colors.accent,
        )
        Text(text = text, style = CrushType.secondary, color = colors.muted)
    }
}

@Composable
fun CrushBanner(
    text: String,
    tone: CrushTone,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = LocalCrushColors.current
    val tint = when (tone) {
        CrushTone.Neutral -> colors.muted
        CrushTone.Accent -> colors.accent
        CrushTone.Success -> colors.success
        CrushTone.Warning -> colors.warning
        CrushTone.Error -> colors.error
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = CrushShape.standard,
        color = colors.surfaceVariant,
        border = BorderStroke(CrushDimens.hairline, colors.separator),
    ) {
        Column(Modifier.padding(CrushSpacing.md)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .size(8.dp)
                        .background(tint, CircleShape)
                )
                Spacer(Modifier.width(CrushSpacing.sm))
                Text(text = text, style = CrushType.secondary, color = colors.ink)
            }
            if (actionLabel != null && onAction != null) {
                TextButton(
                    onClick = onAction,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(actionLabel, style = CrushType.control)
                }
            }
        }
    }
}

@Composable
fun CrushButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    variant: CrushButtonVariant = CrushButtonVariant.Primary,
) {
    val sized = modifier.heightIn(min = CrushDimens.touchTarget)
    val label: @Composable () -> Unit = {
        Text(text = text, style = CrushType.control)
    }
    when (variant) {
        CrushButtonVariant.Primary -> Button(onClick, sized, enabled = enabled) { label() }
        CrushButtonVariant.Secondary -> OutlinedButton(onClick, sized, enabled = enabled) { label() }
        CrushButtonVariant.TextOnly -> TextButton(onClick, sized, enabled = enabled) { label() }
    }
}

@Composable
fun CrushIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(CrushDimens.touchTarget),
        enabled = enabled,
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrushSheet(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = CrushShape.sheet,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CrushSpacing.xl)
                .padding(bottom = CrushSpacing.xl),
        ) {
            Text(text = title, style = CrushType.section)
            Spacer(Modifier.height(CrushSpacing.md))
            content()
        }
    }
}

@Composable
fun CrushDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = "Cancel",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, style = CrushType.section) },
        text = { Text(text = text, style = CrushType.body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, style = CrushType.control)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissLabel, style = CrushType.control)
            }
        },
    )
}
