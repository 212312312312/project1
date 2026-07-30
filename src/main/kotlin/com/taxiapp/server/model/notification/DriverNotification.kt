package com.taxiapp.server.model.notification

import com.taxiapp.server.model.user.Driver
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "driver_notifications")
class DriverNotification(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_id", nullable = false)
    val driver: Driver,

    @Column(nullable = false)
    val title: String,

    @Column(nullable = false)
    val body: String,

    @Column(nullable = false)
    val type: String, // "PAYMENT", "ORDER_CANCEL", "SYSTEM"

    @Column(nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(nullable = false)
    var isRead: Boolean = false
)