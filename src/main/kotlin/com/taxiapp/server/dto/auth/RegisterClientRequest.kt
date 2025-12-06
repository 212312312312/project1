package com.taxiapp.server.dto.auth

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class RegisterClientRequest(
    @field:NotBlank(message = "Номер телефона не может быть пустым")
    val phoneNumber: String,

    @field:NotBlank(message = "Пароль не может быть пустым")
    @field:Size(min = 6, message = "Пароль должен содержать минимум 6 символов")
    val password: String,

    @field:NotBlank(message = "ФИО не может быть пустым")
    val fullName: String
)