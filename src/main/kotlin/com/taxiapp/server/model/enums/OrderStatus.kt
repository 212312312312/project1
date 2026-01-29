package com.taxiapp.server.model.enums

enum class OrderStatus {
    SCHEDULED, // <--- НОВИЙ СТАТУС ДЛЯ ЗАПЛАНОВАНИХ
    REQUESTED,
    OFFERING,
    ACCEPTED,
    DRIVER_ARRIVED,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED
}