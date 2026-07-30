package com.taxiapp.server.model.promo

import jakarta.persistence.*
import java.time.LocalDateTime

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