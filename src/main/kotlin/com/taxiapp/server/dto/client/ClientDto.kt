package com.taxiapp.server.dto.client

import com.taxiapp.server.model.user.Client

data class ClientDto(
    val id: Long,
    val phoneNumber: String, 
    val fullName: String,
    val isBlocked: Boolean,
    val cardMask: String? // <-- НОВОЕ ПОЛЕ
) {
    constructor(client: Client) : this(
        id = client.id ?: 0L,
        phoneNumber = client.userPhone ?: "Не вказано",
        fullName = client.fullName ?: "Невідомий користувач",
        isBlocked = client.isBlocked,
        cardMask = client.cardMask // <-- Читаем из модели
    )
}