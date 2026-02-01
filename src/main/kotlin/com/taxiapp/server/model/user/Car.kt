package com.taxiapp.server.model.user

import com.taxiapp.server.model.enums.CarStatus
import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.persistence.*

@Entity
@Table(name = "cars")
class Car(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_id", nullable = false)
    @JsonIgnore
    var driver: Driver? = null,

    @Column(nullable = false)
    var make: String,

    @Column(nullable = false)
    var model: String,

    @Column(nullable = false)
    var color: String,

    @Column(name = "plate_number", nullable = false)
    var plateNumber: String,

    @Column(nullable = false)
    var vin: String,

    @Column(name = "manufacture_year", nullable = false)
    var year: Int,

    @Column(name = "car_type")
    var carType: String? = null,

    // Основне фото (загальне)
    @Column(name = "photo_url")
    var photoUrl: String? = null,

    // --- ДОКУМЕНТИ ---
    @Column(name = "tech_passport_front")
    var techPassportFront: String? = null,

    @Column(name = "tech_passport_back")
    var techPassportBack: String? = null,

    @Column(name = "insurance_photo")
    var insurancePhoto: String? = null,

    // --- ФОТО АВТО (6 СТОРІН) ---
    @Column(name = "photo_front")
    var photoFront: String? = null,

    @Column(name = "photo_back")
    var photoBack: String? = null,

    @Column(name = "photo_left")
    var photoLeft: String? = null,

    @Column(name = "photo_right")
    var photoRight: String? = null,

    @Column(name = "photo_seats_front")
    var photoSeatsFront: String? = null,

    @Column(name = "photo_seats_back")
    var photoSeatsBack: String? = null,

    @Enumerated(EnumType.STRING)
    var status: CarStatus = CarStatus.ACTIVE, // Для старых машин по умолчанию ACTIVE

    var rejectionReason: String? = null // Причина отказа, если статус REJECTED
)