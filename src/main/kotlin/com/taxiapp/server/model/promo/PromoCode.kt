package com.taxiapp.server.model.promo

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "promo_codes")
data class PromoCode(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(unique = true, nullable = false)
    val code: String,

    @Column(name = "discount_percent", nullable = false)
    val discountPercent: Double,

    @Column(name = "max_discount_amount")
    val maxDiscountAmount: Double? = null,

    @Column(name = "usage_limit")
    val usageLimit: Int? = null,

    @Column(name = "used_count")
    var usedCount: Int = 0,

    // Срок, ДО которого нужно активировать код (Глобальный дедлайн)
    @Column(name = "expires_at")
    val expiresAt: LocalDateTime? = null,

    // НОВОЕ ПОЛЕ: Сколько часов действует скидка ПОСЛЕ активации клиентом
    @Column(name = "activation_duration_hours")
    val activationDurationHours: Int? = null,

    @Column(name = "created_at")
    val createdAt: LocalDateTime = LocalDateTime.now()
)