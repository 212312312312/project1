package com.taxiapp.server.dto.auth

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern

data class SmsRequestDto(
    @field:NotBlank(message = "Введіть номер телефону")
    // БУЛО: ^(0)[0-9]{9}$
    // СТАЛО: Дозволяємо +380... АБО 0...
    @field:Pattern(
        regexp = "^(\\+380|0)[0-9]{9}$", 
        message = "Невірний формат номера"
    )
    val phoneNumber: String
)