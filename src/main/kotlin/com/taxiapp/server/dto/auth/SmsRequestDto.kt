package com.taxiapp.server.dto.auth

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern

data class SmsRequestDto(
    @field:NotBlank
    @field:Pattern(regexp = "^(0)[0-9]{9}$", message = "Номер телефону має бути у форматі 0XXXXXXXXX")
    val phoneNumber: String
    // val isLogin: Boolean <-- ВИДАЛЕНО
)