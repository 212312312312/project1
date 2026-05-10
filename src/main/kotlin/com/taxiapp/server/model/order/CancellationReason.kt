package com.taxiapp.server.model.order

import jakarta.persistence.*

@Entity
@Table(name = "cancellation_reasons")
data class CancellationReason(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false)
    val reasonText: String, 

    @Column(nullable = false)
    val penaltyScore: Int = 0, 

    @Column(nullable = false)
    val isActive: Boolean = true,

    // НОВЕ ПОЛЕ: Вказує, для кого ця причина ("CLIENT" або "DRIVER")
    @Column(nullable = false)
    var target: String = "DRIVER"
)