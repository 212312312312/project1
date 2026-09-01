package com.taxiapp.server.model.support

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

enum class MessageSenderType {
    CLIENT, DISPATCHER, SYSTEM
}

@Entity
@Table(name = "support_messages")
data class SupportMessage(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_id", nullable = false)
    val ticket: SupportTicket,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val senderType: MessageSenderType,

    @Column(columnDefinition = "TEXT", nullable = false)
    val text: String,

    val telegramMessageId: Long? = null,

    // ➕ НОВІ ПОЛЯ ДЛЯ МЕДІА:
    @Column(name = "media_url", columnDefinition = "TEXT")
    val mediaUrl: String? = null,

    @Column(name = "media_type")
    val mediaType: String? = null, // "PHOTO", "VIDEO", "DOCUMENT"

    @Column(nullable = false)
    val createdAt: Instant = Instant.now()
)