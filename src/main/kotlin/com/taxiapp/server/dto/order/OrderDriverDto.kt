package com.taxiapp.server.dto.order

import com.taxiapp.server.model.user.Driver

data class OrderDriverDto(
    val id: Long,
    val fullName: String,
    val phoneNumber: String,
    val carModel: String?, 
    val carColor: String?,
    val carPlateNumber: String?
) {
    constructor(driver: Driver) : this(
        id = driver.id,
        fullName = driver.fullName,
        // --- ВИПРАВЛЕННЯ ТУТ ---
        phoneNumber = driver.userPhone!!, // Було driver.phoneNumber, стало driver.userPhone!!
        // ---
        carModel = driver.car?.let { "${it.make} ${it.model}" },
        carColor = "Стандарт", 
        carPlateNumber = driver.car?.plateNumber
    )
}