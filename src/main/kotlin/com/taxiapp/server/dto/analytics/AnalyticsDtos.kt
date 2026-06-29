package com.taxiapp.server.dto.analytics

// DTO для получения пачки событий от мобильного приложения
data class ClientEventBatchRequest(
    val sessionId: String,
    val utmSource: String?,
    val utmMedium: String?,
    val utmCampaign: String?,
    val events: List<ScreenEventDto>
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
    val trafficStats: List<TrafficSourceStatDto>
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
    val userCount: Long
)