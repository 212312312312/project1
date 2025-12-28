package com.taxiapp.server.model.order

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "car_tariffs")
data class CarTariff(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(unique = true, nullable = false)
    var name: String, // Название (Standard, Premium...)

    @Column(nullable = false)
    var basePrice: Double, // Цена подачи (минималка)

    @Column(nullable = false)
    var pricePerKm: Double, // Цена за 1 км

    @Column(nullable = false)
    var freeWaitingMinutes: Int = 3, // Бесплатные минуты ожидания (для логики расчета)

    @Column(nullable = false)
    var pricePerWaitingMinute: Double = 2.0, // Цена за минуту ожидания

    @Column(nullable = false)
    var isActive: Boolean = true, // Активен ли тариф (отображать ли его в приложении)

    // Ссылка на иконку
    @Column(nullable = true)
    var imageUrl: String? = null, 

    @Column(nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now()
)