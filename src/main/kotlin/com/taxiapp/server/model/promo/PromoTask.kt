package com.taxiapp.server.model.promo

import jakarta.persistence.*

@Entity
@Table(name = "promo_tasks")
data class PromoTask(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    val title: String, // Например: "Активний Райдер"

    @Column(nullable = false)
    val description: String, // Например: "Зроби 5 поїздок"

    @Column(nullable = false)
    val requiredRides: Int, // Скільки треба зробити (напр. 5)

    @Column(nullable = false)
    val discountPercent: Double, // Який відсоток знижки (напр. 15.0)

    @Column(nullable = false)
    val isActive: Boolean = true // Чи активна ця акція взагалі
)