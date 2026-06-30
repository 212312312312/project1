package com.taxiapp.server.dto.analytics

// DTO для получения пачки событий от мобильного приложения
data class ClientEventBatchRequest(
    val sessionId: String,
    val utmSource: String?,
    val utmMedium: String?,
    val utmCampaign: String?,
    val events: List<ScreenEventDto>,
    val customEvents: List<CustomEventDto> = emptyList() // Добавили поле для кликов
)
data class CustomEventDto(
    val eventName: String,
    val eventValue: String?
)

data class ActionStatDto(
    val actionName: String,
    val actionValue: String?,
    val count: Long
)
data class ScreenEventDto(
    val screenName: String,
    val durationSeconds: Long
)

// DTO для отдачи агрегированной аналитики в React панель диспетчера
data class GeneralAnalyticsResponse(
    val averageOrderValue: Double,
    val totalLtvSum: Double,
    val averageLtv: Double,
    val conversionRate: Double,
    val tariffStats: List<TariffStatDto>,
    val screenStats: List<ScreenStatDto>,
    val trafficStats: List<TrafficSourceStatDto>,
    val actionStats: List<ActionStatDto> // Добавили поле со статистикой кликов диспетчеру
)

data class TariffStatDto(
    val tariffName: String,
    val orderCount: Long,
    val totalRevenue: Double
)

data class ScreenStatDto(
    val screenName: String,
    val visitCount: Long,
    val averageDurationSeconds: Double
)

data class TrafficSourceStatDto(
    val source: String,
    val medium: String,
    val campaign: String,
    val userCount: Long
)