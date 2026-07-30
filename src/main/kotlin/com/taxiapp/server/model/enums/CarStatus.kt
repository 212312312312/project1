package com.taxiapp.server.model.enums

enum class CarStatus {
    ACTIVE,     // Одобрена и работает
    PENDING,    // На проверке (только что добавлена)
    REJECTED,   // Отклонена диспетчером
    BLOCKED     // Заблокирована
}