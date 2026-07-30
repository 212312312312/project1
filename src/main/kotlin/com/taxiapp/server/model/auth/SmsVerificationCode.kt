package com.taxiapp.server.model.auth

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "sms_verification_codes")
data class SmsVerificationCode(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,

    @Column(unique = true, nullable = false)
    var userPhone: String, // Номер телефона

    @Column(nullable = false)
    var code: String, // 6-значный код

    @Column(nullable = false)
    var expiresAt: LocalDateTime // Время, до которого код действителен
)