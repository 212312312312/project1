package com.taxiapp.server.dto.client

import com.taxiapp.server.model.user.Client
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class ClientDto(
    val id: Long,
    val phoneNumber: String,
    val fullName: String,
    val email: String?,
    val isBlocked: Boolean,
    val cardMask: String?,
    val tripsCount: Int,
    val rating: Double = 5.0
) {
    constructor(client: Client) : this(
        id = client.id ?: 0L,
        phoneNumber = client.userPhone ?: "Не вказано",
        fullName = client.fullName ?: "Невідомий користувач",
        email = client.email,
        isBlocked = client.isBlocked,
        cardMask = client.cardMask,
        tripsCount = client.tripsCount,
        rating = client.rating
    )
}

data class UpdateClientProfileRequest(
    @field:NotBlank(message = "Ім'я не може бути порожнім")
    @field:Size(min = 2, max = 50, message = "Ім'я повинно містити від 2 до 50 символів")
    val fullName: String
)