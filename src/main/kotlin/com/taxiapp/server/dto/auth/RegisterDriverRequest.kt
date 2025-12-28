package com.taxiapp.server.dto.auth

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class RegisterDriverRequest(
    @field:NotBlank(message = "Номер телефону не може бути порожнім")
    val phoneNumber: String,

    @field:NotBlank(message = "Пароль не може бути порожнім")
    @field:Size(min = 6, message = "Пароль повинен містити мінімум 6 символів")
    val password: String,

    @field:NotBlank(message = "ПІБ не може бути порожнім")
    val fullName: String,

    // Car data
    @field:NotBlank(message = "Марка авто не може бути порожньою")
    val make: String,

    @field:NotBlank(message = "Модель авто не може бути порожньою")
    val model: String,

    @field:NotBlank(message = "Колір авто не може бути порожнім")
    val color: String, // <-- НОВОЕ ПОЛЕ

    @field:NotBlank(message = "Номер авто не може бути порожнім")
    val plateNumber: String,

    @field:NotBlank(message = "VIN не може бути порожнім")
    val vin: String,

    @field:Min(value = 1990, message = "Рік випуску повинен бути не раніше 1990")
    val year: Int,
    
    val tariffIds: List<Long> = emptyList() 
)