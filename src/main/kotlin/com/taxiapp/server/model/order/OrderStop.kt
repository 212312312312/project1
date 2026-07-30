package com.taxiapp.server.model.order

import com.fasterxml.jackson.annotation.JsonBackReference
import jakarta.persistence.*

@Entity
@Table(name = "order_stops")
data class OrderStop(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false)
    var address: String,

    @Column(nullable = false)
    var lat: Double,

    @Column(nullable = false)
    var lng: Double,
    
    // Порядковий номер зупинки (щоб знати послідовність: 1, 2, 3...)
    @Column(name = "stop_order")
    var stopOrder: Int,

    // Зв'язок з головним замовленням
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    @JsonBackReference
    var order: TaxiOrder? = null
) {
    // Порожній конструктор для Hibernate
    constructor() : this(null, "", 0.0, 0.0, 0, null)
}