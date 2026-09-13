package com.taxiapp.server.model.support

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "support_blocked_users")
data class SupportBlockedUser(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(nullable = false, unique = true)
    val telegramChatId: Long,

    @Column(nullable = false)
    val phoneNumber: String,

    val userName: String? = null,

    val reason: String? = null,

    @Column(nullable = false)
    val blockedAt: Instant = Instant.now()
)