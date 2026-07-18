package de.hoshi.core.dto

/** Eine Nachricht in der Chat-Historie. role: "user" | "assistant" | "system". */
data class ChatMessage(
    val role: String,
    val content: String,
)
