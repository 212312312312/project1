package com.taxiapp.server.model.promo

import com.taxiapp.server.model.order.CarTariff
import jakarta.persistence.*

@Entity
@Table(name = "promo_tasks")
data class PromoTask(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    var title: String,

    @Column(nullable = false)
    var description: String,

    @Column(nullable = false)
    var requiredRides: Int = 0, 

    // Дистанция (в метрах)
    @Column(nullable = false)
    var requiredDistanceMeters: Long = 0,

    @Column(nullable = false)
    var discountPercent: Double,

    @Column(nullable = false)
    var isActive: Boolean = true,

    // Ваша связь с тарифом (объект)
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "required_tariff_id")
    var requiredTariff: CarTariff? = null,

    // Флаг одноразовой акции
    @Column(nullable = false)
    var isOneTime: Boolean = false,

    @Column(name = "max_discount_amount")
    var maxDiscountAmount: Double? = null,

    @Column(name = "active_days_duration")
    var activeDaysDuration: Int? = null
)