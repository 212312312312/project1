package com.taxiapp.server.model.order

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "taxi_order_tracks")
class TaxiOrderTrack(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    val orderId: Long,

    @Column(nullable = false)
    val latitude: Double,

    @Column(nullable = false)
    val longitude: Double,

    @Column(nullable = false)
    val timestamp: LocalDateTime = LocalDateTime.now()
)