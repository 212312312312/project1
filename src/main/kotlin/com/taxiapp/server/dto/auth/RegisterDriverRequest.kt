package com.taxiapp.server.dto.auth

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

@JsonIgnoreProperties(ignoreUnknown = true)
data class RegisterDriverRequest(
    @field:NotBlank(message = "Номер телефону не може бути порожнім")
    val phoneNumber: String,

    @field:NotBlank(message = "SMS код не може бути порожнім")
    val smsCode: String, 

    @field:NotBlank(message = "Пароль не може бути порожнім")
    @field:Size(min = 6, message = "Пароль повинен містити мінімум 6 символів")
    val password: String,

    @field:NotBlank(message = "ПІБ не може бути порожнім")
    val fullName: String,

    val email: String? = null,
    val rnokpp: String? = null,
    val driverLicense: String? = null,
    
    @field:NotBlank(message = "Місто не може бути порожнім")
    val city: String,

    @field:NotBlank(message = "Марка авто не може бути порожньою")
    val make: String,

    @field:NotBlank(message = "Модель авто не може бути порожньою")
    val model: String,

    @field:NotBlank(message = "Колір авто не може бути порожнім")
    val color: String,

    @field:NotBlank(message = "Номер авто не може бути порожнім")
    val plateNumber: String,

    @field:Min(value = 1990, message = "Рік випуску повинен бути не раніше 1990")
    val year: Int,

    val carType: String,
    
    val tariffIds: List<Long> = emptyList() 
)