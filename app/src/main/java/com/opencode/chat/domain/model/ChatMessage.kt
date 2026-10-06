package com.opencode.chat.domain.model

data class ChatMessage(
    val id: String,
    val sessionID: String = "local",
    val role: MessageRole,
    val content: String = "",
    val createdAt: Long = 0,
    val isStreaming: Boolean = false
)

enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}
