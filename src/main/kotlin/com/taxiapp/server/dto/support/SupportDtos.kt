package com.taxiapp.server.dto.support

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.taxiapp.server.model.support.MessageSenderType
import com.taxiapp.server.model.support.TicketStatus
import java.time.Instant
import java.util.UUID

// ===== DTO для Telegram Webhook =====
@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramUpdate(
    @JsonProperty("update_id") val updateId: Long,
    @JsonProperty("message") val message: TelegramMessage? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramPhotoSize(
    @JsonProperty("file_id") val fileId: String,
    @JsonProperty("file_unique_id") val fileUniqueId: String,
    val width: Int,
    val height: Int,
    @JsonProperty("file_size") val fileSize: Long? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramVideo(
    @JsonProperty("file_id") val fileId: String,
    @JsonProperty("file_unique_id") val fileUniqueId: String,
    val width: Int,
    val height: Int,
    val duration: Int,
    @JsonProperty("file_size") val fileSize: Long? = null,
    @JsonProperty("mime_type") val mimeType: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramUser(
    val id: Long,
    @JsonProperty("first_name") val firstName: String? = null,
    @JsonProperty("last_name") val lastName: String? = null,
    val username: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramChat(
    val id: Long,
    val type: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramContact(
    @JsonProperty("phone_number") val phoneNumber: String,
    @JsonProperty("first_name") val firstName: String? = null,
    @JsonProperty("user_id") val userId: Long? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramMessage(
    @JsonProperty("message_id") val messageId: Long,
    @JsonProperty("from") val from: TelegramUser? = null,
    @JsonProperty("chat") val chat: TelegramChat,
    @JsonProperty("text") val text: String? = null,
    @JsonProperty("caption") val caption: String? = null,
    @JsonProperty("photo") val photo: List<TelegramPhotoSize>? = null,
    @JsonProperty("video") val video: TelegramVideo? = null,
    @JsonProperty("contact") val contact: TelegramContact? = null
)

// ===== DTO для Диспетчерської (React) =====
data class SupportTicketDto(
    val id: UUID,
    val telegramChatId: Long,
    val phoneNumber: String,
    val userName: String?,
    val userRole: String?,
    val status: TicketStatus,
    val lastMessage: String?,
    val unreadCount: Int = 0,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class SupportMessageDto(
    val id: UUID,
    val ticketId: UUID,
    val senderType: MessageSenderType,
    val text: String,
    val mediaUrl: String? = null,
    val mediaType: String? = null,
    val createdAt: Instant
)

data class SendSupportReplyRequest(
    val text: String
)