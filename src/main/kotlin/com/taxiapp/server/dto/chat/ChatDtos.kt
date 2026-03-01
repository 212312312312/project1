package com.taxiapp.server.dto.chat

import java.time.LocalDateTime

data class ChatMessageDto(
    val id: Long?,
    val orderId: Long,
    val senderRole: String,
    val senderId: Long,
    val content: String,
    val createdAt: LocalDateTime
)

data class SendMessageRequest(
    val content: String
)