package com.taxiapp.server.dto.auth

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class LoginRequest(
    @field:NotBlank(message = "Логин не может быть пустым")
    val login: String, // <-- ИЗМЕНЕНО (было phoneNumber)

    @field:NotBlank(message = "Пароль не может быть пустым")
    @field:Size(min = 6, message = "Пароль должен содержать минимум 6 символов")
    val password: String
)