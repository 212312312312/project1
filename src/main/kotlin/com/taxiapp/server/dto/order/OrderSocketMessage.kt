package com.taxiapp.server.dto.order

data class OrderSocketMessage(
    val action: String,    // "ADD" или "REMOVE"
    val orderId: String,   // <-- ИЗМЕНИЛИ С Long НА String
    val order: TaxiOrderDto? = null
)