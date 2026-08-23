package com.taxiapp.server.model.support

import com.taxiapp.server.model.user.User
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

enum class TicketStatus {
    OPEN, IN_PROGRESS, CLOSED
}

@Entity
@Table(name = "support_tickets")
data class SupportTicket(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(nullable = false)
    val telegramChatId: Long,

    @Column(nullable = false)
    var phoneNumber: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: TicketStatus = TicketStatus.OPEN,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    var user: User? = null,

    @Column(nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(nullable = false)
    var updatedAt: Instant = Instant.now()
)