package com.taxiapp.server.dto.driver

import java.time.LocalDateTime

data class DriverActivityDto(
    val score: Int,
    val level: ActivityLevel,
    val history: List<ActivityHistoryItemDto>
)

data class ActivityHistoryItemDto(
    val change: Int,
    val reason: String,
    val date: LocalDateTime,
    val orderUuid: String? = null // Передаем UUID на клиент
)

enum class ActivityLevel {
    GREEN,  // 1000 - 701
    YELLOW, // 700 - 401
    RED,    // 400 - 1
    BLOCKED // 0
}