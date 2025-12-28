package com.taxiapp.server.model.services

import jakarta.persistence.*

@Entity
@Table(name = "taxi_services")
class TaxiServiceEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    val name: String,

    // Если 0.0, то бесплатно
    val price: Double = 0.0,
    
    var isActive: Boolean = true
)