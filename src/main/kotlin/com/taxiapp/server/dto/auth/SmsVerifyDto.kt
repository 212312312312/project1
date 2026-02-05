package com.taxiapp.server.dto.auth

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern

data class SmsVerifyDto(
    @field:NotBlank(message = "Введіть номер телефону")
    @field:Pattern(
        regexp = "^(\\+380|0)[0-9]{9}$", 
        message = "Невірний формат номера"
    )
    val phoneNumber: String,

    @field:NotBlank(message = "Введіть код")
    val code: String
)