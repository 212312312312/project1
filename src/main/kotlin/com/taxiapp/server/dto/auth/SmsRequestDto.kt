package com.taxiapp.server.dto.auth

import com.fasterxml.jackson.annotation.JsonAlias
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern

data class SmsRequestDto(
    @field:NotBlank(message = "Введіть номер телефону")
    @field:Pattern(
        regexp = "^(\\+380|0)[0-9]{9}$", 
        message = "Невірний формат номера"
    )
    @JsonAlias("phone")
    val phoneNumber: String
)