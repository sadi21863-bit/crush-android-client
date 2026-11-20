package com.crush.mobile.ui.screens.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.crush.mobile.ui.components.CrushBanner
import com.crush.mobile.ui.components.CrushButton
import com.crush.mobile.ui.components.CrushButtonVariant
import com.crush.mobile.ui.components.CrushCard
import com.crush.mobile.ui.components.CrushEmptyState
import com.crush.mobile.ui.components.CrushLoadingRow
import com.crush.mobile.ui.components.CrushTone
import com.crush.mobile.ui.theme.CrushSpacing
import com.crush.mobile.ui.theme.CrushType
import com.crush.mobile.ui.theme.LocalCrushColors

/**
 * V2-5 IA: the list-first home for both modes (owner sketch, 2026-11-20).
 *
 * Crush Chat shows the legacy workspace's chats; Crush Code shows the active
 * project's sessions. It is the same projected [ChatViewState] and the same
 * [ChatAction] sink - no second source of truth.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListContent(
    state: ChatViewState,
    onAction: (ChatAction) -> Unit,
) {
    val isCode = state.binding.kind == BindingKind.Project
    val title = if (isCode) {
        state.binding.projectLabel?.takeIf { it.isNotBlank() } ?: "Crush Code"
    } else {
        "Crush Chat"
    }
    val now = remember { System.currentTimeMillis() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            title,
                            style = CrushType.section,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            if (isCode) "Code sessions" else "Chats",
                            style = CrushType.metadata,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    if (isCode) {
                        TextButton(onClick = { onAction(ChatAction.OpenReview) }) {
                            Text("Review", style = CrushType.control)
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = CrushSpacing.lg)
        ) {
            Spacer(Modifier.height(CrushSpacing.sm))
            CrushButton(
                text = if (isCode) "New session" else "New chat",
                onClick = { onAction(ChatAction.NewChat) },
                modifier = Modifier.fillMaxWidth(),
                variant = CrushButtonVariant.Primary,
            )
            Spacer(Modifier.height(CrushSpacing.md))

            val list = state.session.list
            val engineReady = state.run.engineReady
            when {
                list.loading && list.items.isEmpty() -> CrushLoadingRow(
                    text = "Loading chats...",
                    modifier = Modifier.padding(vertical = CrushSpacing.lg)
                )

                list.items.isEmpty() && (!engineReady || list.unavailable) -> {
                    CrushBanner(
                        text = if (isCode) {
                            "Sessions aren't available. Start one to connect this project's engine."
                        } else {
                            "Chats aren't available. Start one to connect the engine."
                        },
                        tone = CrushTone.Warning,
                    )
                    Spacer(Modifier.height(CrushSpacing.md))
                    Text(
                        if (isCode) {
                            "The project engine is not running, so its session list has not been loaded."
                        } else {
                            "The Chat engine is not running, so the chat list has not been loaded."
                        },
                        style = CrushType.body,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                list.items.isEmpty() -> CrushEmptyState(
                    title = if (isCode) "No sessions yet" else "No chats yet",
                    body = if (isCode) {
                        "Start a session in this project. Its conversation stays on this project's engine."
                    } else {
                        "Start a chat. Crush Chat answers questions without running tools."
                    },
                )

                else -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(CrushSpacing.sm),
                    modifier = Modifier.weight(1f)
                ) {
                    items(list.items, key = { it.id }) { session ->
                        SessionRow(
                            session = session,
                            nowMs = now,
                            onClick = { onAction(ChatAction.SelectSession(session.id)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionRow(
    session: SessionItem,
    nowMs: Long,
    onClick: () -> Unit,
) {
    val colors = LocalCrushColors.current
    CrushCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        session.label,
                        style = CrushType.body,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (session.isActive) {
                        Spacer(Modifier.width(CrushSpacing.sm))
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = colors.accent,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(CrushSpacing.xs))
                        Text("Current", style = CrushType.metadata, color = colors.muted)
                    }
                }
                val detail = SessionList.detail(
                    messageCount = session.messageCount,
                    cost = session.cost,
                    isBusy = session.isBusy,
                    updatedAt = session.updatedAt,
                    nowMs = nowMs,
                )
                if (detail.isNotEmpty()) {
                    Text(
                        detail,
                        style = CrushType.metadata,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
