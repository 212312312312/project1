package com.taxiapp.server.model.auth

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "blacklist")
data class Blacklist(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(unique = true, nullable = false)
    val phoneNumber: String,

    val reason: String? = null,
    val bannedAt: LocalDateTime = LocalDateTime.now()
)