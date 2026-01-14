package com.taxiapp.server.model.enums

enum class OrderStatus {
    REQUESTED,
    OFFERING, // <--- ДОДАЙ ЦЕЙ СТАТУС
    ACCEPTED,
    DRIVER_ARRIVED,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED
}