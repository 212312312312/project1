package com.taxiapp.server.model.user

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "cars")
data class Car(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    var make: String, // Марка

    @Column(nullable = false)
    var model: String, // Модель

    @Column(nullable = false)
    var plateNumber: String, // Номер

    @Column(nullable = false)
    var vin: String, // VIN

    @Column(nullable = false)
    var year: Int
)