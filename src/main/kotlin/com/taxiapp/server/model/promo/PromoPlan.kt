package com.taxiapp.server.model.promo

import jakarta.persistence.*
import java.time.LocalDateTime


enum class PromoPlanType {
    FREE_MINIMUM,
    REGISTRATION_DISCOUNT
}

@Entity
@Table(name = "promo_plans")
data class PromoPlan(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    val title: String,

    @Column(length = 1000)
    val description: String?,

    // ➕ НОВЫЕ ПОЛЯ ДЛЯ СКИДКИ ПРИ РЕГИСТРАЦИИ:
    @Enumerated(EnumType.STRING)
    @Column(name = "plan_type", nullable = false)
    var planType: PromoPlanType = PromoPlanType.FREE_MINIMUM,

    @Column(name = "discount_percent")
    var discountPercent: Double? = null,

    @Column(name = "max_discount_amount")
    var maxDiscountAmount: Double? = null,

    @Column(name = "validity_hours")
    var validityHours: Int? = null, // Срок жизни скидки для клиента в часах
    // ---------------------------------------------

    @Column(name = "max_uses")
    var maxUses: Int? = null,

    @Column(name = "start_date", nullable = false)
    val startDate: LocalDateTime,

    @Column(name = "end_date", nullable = false)
    val endDate: LocalDateTime,

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true,

    @Column(name = "created_at")
    val createdAt: LocalDateTime = LocalDateTime.now()
)