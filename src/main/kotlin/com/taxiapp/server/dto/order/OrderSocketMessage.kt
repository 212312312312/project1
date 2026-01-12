package com.taxiapp.server.dto.order

data class OrderSocketMessage(
    val action: String,    // "ADD" или "REMOVE"
    val orderId: Long,
    val order: TaxiOrderDto? = null
)