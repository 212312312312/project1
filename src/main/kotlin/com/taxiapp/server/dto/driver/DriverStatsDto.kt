package com.taxiapp.server.dto.driver

data class ChartPointDto(
    val date: String,   // Дата в формате "2026-06-08"
    val income: Double  // Чистый заработок водителя конкретно за этот день (уже без комиссии)
)

data class DriverStatsDto(
    val totalIncome: Double,      // Общий доход (чистыми)
    val commission: Double,       // Комиссия сервиса
    val incomeCard: Double,       // На карту
    val incomeCash: Double,       // Наличные
    val incomeBalance: Double,    // На баланс/Бонусы
    val ordersCount: Int,         // Кол-во заказов
    val totalDistanceKm: Double,  // Километраж
    val avgPricePerKm: Double,    // Средняя цена за км
    val totalHours: Double,        // Часов в работе (на заказах)
    val chartPoints: List<ChartPointDto> // <-- НОВОЕ ПОЛЕ: Реальные точки для графика
)