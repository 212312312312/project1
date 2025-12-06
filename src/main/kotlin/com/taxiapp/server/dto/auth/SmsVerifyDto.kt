package com.taxiapp.server.dto.auth

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

data class SmsVerifyDto(
    @field:NotBlank
    @field:Pattern(regexp = "^(0)[0-9]{9}$", message = "Номер телефона должен быть в формате 0XXXXXXXXX")
    val phoneNumber: String,

    @field:NotBlank
    @field:Size(min = 6, max = 6, message = "Код должен состоять из 6 цифр")
    val code: String
)