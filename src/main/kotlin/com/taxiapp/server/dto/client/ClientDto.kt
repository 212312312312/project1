package com.taxiapp.server.dto.client

import com.taxiapp.server.model.user.Client

data class ClientDto(
    val id: Long,
    val phoneNumber: String, 
    val fullName: String,
    val isBlocked: Boolean
) {
    constructor(client: Client) : this(
        id = client.id,
        phoneNumber = client.userPhone!!, // ИЗМЕНЕНО
        fullName = client.fullName,
        isBlocked = client.isBlocked
    )
}