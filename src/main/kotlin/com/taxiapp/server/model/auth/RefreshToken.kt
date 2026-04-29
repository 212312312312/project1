package com.taxiapp.server.model.auth

import com.taxiapp.server.model.user.User
import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "refresh_tokens")
class RefreshToken {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0

    // Связь с таблицей пользователей
    @OneToOne
    @JoinColumn(name = "user_id", referencedColumnName = "id")
    lateinit var user: User

    @Column(nullable = false, unique = true)
    lateinit var token: String

    @Column(nullable = false)
    lateinit var expiryDate: Instant
}