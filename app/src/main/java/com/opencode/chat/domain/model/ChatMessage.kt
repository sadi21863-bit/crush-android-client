package com.opencode.chat.domain.model

import com.opencode.chat.ui.screens.chat.ToolActivity

data class ChatMessage(
    val id: String,
    val sessionID: String = "local",
    val role: MessageRole,
    val content: String = "",
    val createdAt: Long = 0,
    val isStreaming: Boolean = false,
    /**
     * Tool invocations the agent made while producing this message.
     *
     * Empty for user messages and for assistants that only spoke. Populated from
     * the engine's `tool_call` parts, which were previously parsed and then
     * discarded - the agent was reading files and running commands invisibly.
     */
    val tools: List<ToolActivity> = emptyList()
)

enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}
