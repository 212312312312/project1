package com.taxiapp.server.model.user

import jakarta.persistence.*

@Entity
@Table(name = "cars")
data class Car(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    var make: String,

    @Column(nullable = false)
    var model: String,

    @Column(nullable = false)
    var color: String, // <-- НОВОЕ ПОЛЕ

    @Column(nullable = false)
    var plateNumber: String,

    @Column(nullable = false)
    var vin: String,

    @Column(nullable = false)
    var year: Int
)