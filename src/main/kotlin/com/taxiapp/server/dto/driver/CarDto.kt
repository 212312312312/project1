package com.taxiapp.server.dto.driver

// !!! ВАЖНО: Правильный импорт (model.user, а не model.car)
import com.taxiapp.server.model.user.Car 

data class CarDto(
    val id: Long,
    val make: String,
    val model: String,
    val plateNumber: String,
    val vin: String,
    val year: Int
) {
    constructor(car: Car) : this(
        id = car.id,
        make = car.make,
        model = car.model,
        // Используем plateNumber, как в вашей сущности
        plateNumber = car.plateNumber, 
        vin = car.vin,
        year = car.year
    )
}