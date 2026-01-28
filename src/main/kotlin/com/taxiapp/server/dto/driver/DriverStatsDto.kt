package com.taxiapp.server.dto.driver

data class DriverStatsDto(
    val totalIncome: Double,      // Общий доход (чистыми)
    val commission: Double,       // Комиссия сервиса
    val incomeCard: Double,       // На карту
    val incomeCash: Double,       // Наличные
    val incomeBalance: Double,    // На баланс/Бонусы
    val ordersCount: Int,         // Кол-во заказов
    val totalDistanceKm: Double,  // Километраж
    val avgPricePerKm: Double,    // Средняя цена за км
    val totalHours: Double        // Часов в работе (на заказах)
)