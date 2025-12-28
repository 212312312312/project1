package com.taxiapp.server.dto.driver

import com.taxiapp.server.model.user.Car

data class CarDto(
    val id: Long,
    val make: String,
    val model: String,
    val color: String, // <-- НОВОЕ ПОЛЕ
    val plateNumber: String,
    val vin: String,
    val year: Int
) {
    constructor(car: Car) : this(
        id = car.id,
        make = car.make,
        model = car.model,
        color = car.color, // <-- Маппинг
        plateNumber = car.plateNumber,
        vin = car.vin,
        year = car.year
    )
}