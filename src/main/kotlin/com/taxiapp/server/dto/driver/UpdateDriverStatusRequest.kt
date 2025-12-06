package com.taxiapp.server.dto.driver

import jakarta.validation.constraints.NotNull

// DTO для Водителя: "Выйти на линию" / "Уйти с линии"
data class UpdateDriverStatusRequest(
    @field:NotNull(message = "isOnline не может быть null")
    val isOnline: Boolean,
    
    // Координаты, которые водитель присылает, когда выходит на линию
    val latitude: Double?,
    val longitude: Double?
)