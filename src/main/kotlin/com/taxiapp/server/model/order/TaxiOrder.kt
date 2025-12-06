package com.taxiapp.server.model.order

import com.taxiapp.server.model.enums.OrderStatus
import com.taxiapp.server.model.user.Client
import com.taxiapp.server.model.user.Driver
import com.taxiapp.server.model.order.OrderStop
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import java.time.LocalDateTime

@Entity
@Table(name = "taxi_orders")
data class TaxiOrder(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    val client: Client,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_id")
    var driver: Driver? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: OrderStatus,

    // Текстові адреси
    @Column(nullable = false)
    val fromAddress: String,

    @Column(nullable = false)
    val toAddress: String,
    
    // --- КООРДИНАТИ ---
    @Column(nullable = true) 
    var originLat: Double? = null, 
    
    @Column(nullable = true)
    var originLng: Double? = null,
    
    @Column(nullable = true)
    var destLat: Double? = null,
    
    @Column(nullable = true)
    var destLng: Double? = null,
    // ------------------

    @Column(nullable = true, length = 4096)
    var googleRoutePolyline: String? = null,

    @Column(nullable = false)
    var price: Double = 0.0, 
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tariff_id", nullable = false)
    val tariff: CarTariff, 
    
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    var completedAt: LocalDateTime? = null,

    @Column(nullable = false)
    val appliedDiscount: Double = 0.0

) {
    // !!! НОВЕ ПОЛЕ ДЛЯ ЗУПИНОК !!!
    // Ми винесли його з конструктора в тіло класу, щоб Hibernate працював коректно
    
    @OneToMany(mappedBy = "order", cascade = [CascadeType.ALL], orphanRemoval = true)
    var stops: MutableList<OrderStop> = mutableListOf()
}