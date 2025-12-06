package com.taxiapp.server.dto.driver

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank

data class UpdateDriverRequest(
    @field:NotBlank(message = "ПІБ не може бути порожнім")
    val fullName: String,

    // Дані по авто
    @field:NotBlank(message = "Марка авто не може бути порожньою")
    val make: String,
    //... (всі інші поля авто)
    @field:NotBlank(message = "Модель авто не може бути порожньою")
    val model: String,

    @field:NotBlank(message = "Номер авто не може бути порожнім")
    val plateNumber: String,

    @field:NotBlank(message = "VIN не може бути порожнім")
    val vin: String,

    @field:Min(value = 1990, message = "Рік випуску повинен бути не раніше 1990")
    val year: Int,
    
    // НОВЕ ПОЛЕ: Список ID тарифів, які вибрав диспетчер
    val tariffIds: List<Long> = emptyList()
)