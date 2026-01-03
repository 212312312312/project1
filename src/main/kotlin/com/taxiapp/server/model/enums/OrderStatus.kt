package com.taxiapp.server.model.enums

enum class OrderStatus {
    REQUESTED,      // Поиск водителя
    ACCEPTED,       // Водитель едет к клиенту
    DRIVER_ARRIVED, // <--- НОВОЕ: Водитель на месте
    IN_PROGRESS,    // <--- НОВОЕ: Поездка началась
    COMPLETED,      // Поездка завершена
    CANCELLED       // Отменен
}