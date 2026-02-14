package com.taxiapp.server.model.order

import jakarta.persistence.*

@Entity
@Table(name = "cancellation_reasons")
data class CancellationReason(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false)
    val reasonText: String, // Текст: "Клієнт не вийшов", "Зламалась машина"

    @Column(nullable = false)
    val penaltyScore: Int = 0, // Штраф: 0 или 50 и т.д.

    @Column(nullable = false)
    val isActive: Boolean = true // Чтобы можно было скрывать старые причины
)