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
data class ClientCancellationStatDto(
    val reason: String,
    val count: Long,
    val percentage: Double
)

// DTO для отдачи агрегированной аналитики в React панель диспетчера
data class GeneralAnalyticsResponse(
    val averageOrderValue: Double,
    val totalLtvSum: Double,
    val averageLtv: Double,
    val conversionRate: Double,
    val fulfillmentRate: Double, // <-- НОВОЕ ПОЛЕ (Операционная конверсия поездок)
    val tariffStats: List<TariffStatDto>,
    val screenStats: List<ScreenStatDto>,
    val trafficStats: List<TrafficSourceStatDto>,
    val actionStats: List<ActionStatDto>,
    val clientCancellationStats: List<ClientCancellationStatDto>
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

// ➕ Новые DTO для операционных KPIs, когорт, окупаемости и антифрода:

data class OperationalKpiDto(
    val fulfillmentRate: Double,          // % вывозимости
    val avgTimeToAcceptSeconds: Double,   // Среднее время взятия водителем (сек)
    val boostOrdersPercent: Double,       // % заказов с нажатием +20/+40 грн
    val boostFulfillmentPercent: Double,  // % вывозимости заказов после буста
    val quickClientCancelPercent: Double, // % отмен клиентом в первые 60 сек
    val timeoutCancelPercent: Double      // % отмен по таймауту биржи
)

data class CohortRetentionDto(
    val cohortWeek: String,               // Дата старта когорты (YYYY-WW)
    val totalUsers: Long,                 // Размер когорты
    val ride2RetentionPercent: Double,    // % совершивших 2-ю поездку (цель >= 30%)
    val ride3RetentionPercent: Double,    // % совершивших 3-ю поездку (цель >= 20%)
    val ride5PlusRetentionPercent: Double // % постоянных клиентов (5+ поездок)
)

data class PaybackPeriodDto(
    val estimatedCac: Double,             // Расчетный CAC (например 120 грн)
    val avgCommissionPerOrder: Double,    // Средняя комиссия сервиса (15 грн)
    val targetRidesForPayback: Int,       // Целевое кол-во поездок (8)
    val avgDaysToPayback: Double          // Среднее число дней до 8-й поездки
)

data class FraudMetricDto(
    val blockedPromoAttempts: Long,       // Попытки повторного использования promo с тем же device_id
    val suspiciousDevicesCount: Long      // Кол-во устройств с >1 разными номерами
)

// ➕ Добавить эти структуры в GeneralAnalyticsResponse:
data class DeepAnalyticsResponse(
    val general: GeneralAnalyticsResponse,
    val kpis: OperationalKpiDto,
    val cohorts: List<CohortRetentionDto>,
    val payback: PaybackPeriodDto,
    val fraud: FraudMetricDto
)