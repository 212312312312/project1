package com.taxiapp.server.dto.client

import com.taxiapp.server.model.user.Client

data class ClientDto(
    val id: Long,
    val phoneNumber: String, 
    val fullName: String,
    val isBlocked: Boolean
) {
    constructor(client: Client) : this(
        id = client.id ?: 0L, // На всякий случай защищаем и ID
        phoneNumber = client.userPhone ?: "Не вказано", // <-- ИСПРАВЛЕНО: убрали !!, добавили ?:
        fullName = client.fullName ?: "Невідомий користувач", // Защищаем имя
        isBlocked = client.isBlocked
    )
}