package com.taxiapp.server.dto.order

import com.taxiapp.server.model.user.Client

data class OrderClientDto(
    val id: Long,
    val fullName: String,
    val phoneNumber: String,
    val tripsCount: Int,
    val rating: Double
) {
    constructor(client: Client) : this(
        id = client.id,
        fullName = client.fullName,
        phoneNumber = client.userPhone!!,
        tripsCount = client.tripsCount,
        rating = client.rating 
    )
}