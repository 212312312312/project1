package com.taxiapp.server.dto.dispatcher

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

// DTO для створення/оновлення диспетчера
data class CreateDispatcherRequest(
    @field:NotBlank(message = "Логін не може бути порожнім")
    val userLogin: String,

    @field:NotBlank(message = "ПІБ не може бути порожнім")
    val fullName: String,

    // Пароль є 'nullable':
    // - При Створенні: він має бути (required)
    // - При Оновленні: він null (не змінюємо пароль)
    @field:Size(min = 6, message = "Пароль повинен містити мінімум 6 символів")
    val password: String?
)