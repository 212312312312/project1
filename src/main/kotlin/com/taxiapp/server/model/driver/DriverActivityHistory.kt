package com.taxiapp.server.model.driver

import com.taxiapp.server.model.user.Driver
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import java.time.LocalDateTime

@Entity
@Table(name = "driver_activity_history")
class DriverActivityHistory(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_id", nullable = false)
    var driver: Driver,

    @Column(name = "points_change", nullable = false)
    var pointsChange: Int, // Например: +5 или -50

    @Column(nullable = false)
    var reason: String, // Например: "Замовлення #123 (Ефір)"

    @Column(name = "order_id")
    var orderId: Long? = null, // Ссылка на заказ, если изменение связано с ним

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()
)