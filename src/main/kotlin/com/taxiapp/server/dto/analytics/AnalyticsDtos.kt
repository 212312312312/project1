package com.taxiapp.server.dto.analytics

// DTO для получения пачки событий от мобильного приложения
data class ClientEventBatchRequest(
    val sessionId: String,
    val utmSource: String?,
    val utmMedium: String?,
    val utmCampaign: String?,
    val events: List<ScreenEventDto>,
    val customEvents: List<CustomEventDto> = emptyList()
)

data class CustomEventDto(
    val eventName: String,
    val eventValue: String?
)

data class ActionStatDto(
    val actionName: String,
    val actionValue: String?,
    val count: Long,
    val uniqueSessions: Long = 0L,
    val uniqueUsers: Long = 0L
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

// DTO для ручного внесения доходов / выплат в карточке Чистой прибыли
data class ManualFinanceEntryDto(
    val id: String,
    val type: String, // "INCOME" (+ дохід) или "EXPENSE" (- ручна виплата/витрата)
    val amount: Double,
    val comment: String,
    val createdAt: String
)

data class CreateManualFinanceRequest(
    val type: String = "INCOME", // "INCOME" или "EXPENSE"
    val amount: Double,
    val comment: String? = null
)

// Финансовая Unit-экономика (GMV vs Комиссия vs Ручные корректировки vs Чистая прибыль)
// Точка реального графика доходов и просадок по дням
data class DailyRevenuePointDto(
    val date: String,               // YYYY-MM-DD
    val label: String,              // DD.MM
    val ordersCount: Long = 0L,     // Выполнено поездок за день
    val gmv: Double = 0.0,          // Валовый оборот за день (грн)
    val commission: Double = 0.0,   // Комиссия за день (грн)
    val discounts: Double = 0.0,    // Скидки за день (грн)
    val manualNet: Double = 0.0,    // Ручные доходы минус выплаты за день (грн)
    val netRevenue: Double = 0.0    // Чистый доход компании за день (грн)
)

data class UnitEconomicsDto(
    val grossMerchandiseValue: Double = 0.0,      // GMV (общий оборот завершенных поездок)
    val totalCommissionRevenue: Double = 0.0,     // Все деньги, полученные за комиссию (wallet_transactions + заказы)
    val totalPromoSubsidies: Double = 0.0,        // Сумма скидок и промо-доплат (Promo Burn)
    val manualIncome: Double = 0.0,               // Вписанные вручную доходы / пополнения (+)
    val manualExpense: Double = 0.0,              // Вписанные вручную выплаты / расходы (-)
    val netCompanyRevenue: Double = 0.0,          // Чистый доход компании (Комиссия + Ручной доход - Ручные выплаты - Скидки)
    val totalWaitingRevenue: Double = 0.0,        // Доход от платного простоя
    val avgGrossCheck: Double = 0.0,              // Средний чек до скидки
    val avgClientPayCheck: Double = 0.0,          // Средний чек, оплаченный клиентом
    val avgNetCommissionPerOrder: Double = 0.0,   // Чистая прибыль компании на 1 заказ
    val discountedOrdersCount: Long = 0L,         // Завершенных поездок со скидкой
    val discountedOrdersSharePercent: Double = 0.0, // % поездок со скидкой
    val manualEntries: List<ManualFinanceEntryDto> = emptyList(),
    val revenueTimeline: List<DailyRevenuePointDto> = emptyList() // Реальная динамика дохода по дням
)

// DTO для отдачи агрегированной аналитики в React панель диспетчера
data class GeneralAnalyticsResponse(
    val averageOrderValue: Double,
    val totalLtvSum: Double,
    val averageLtv: Double,
    val conversionRate: Double,
    val fulfillmentRate: Double,
    val tariffStats: List<TariffStatDto>,
    val screenStats: List<ScreenStatDto>,
    val trafficStats: List<TrafficSourceStatDto>,
    val actionStats: List<ActionStatDto>,
    val clientCancellationStats: List<ClientCancellationStatDto>,
    val totalOrdersCount: Long = 0L,
    val completedOrdersCount: Long = 0L,
    val cancelledOrdersCount: Long = 0L,
    val totalClientsCount: Long = 0L,
    val uniquePayingClientsCount: Long = 0L,
    val unitEconomics: UnitEconomicsDto = UnitEconomicsDto(),
    val sla: SlaMetricsDto = SlaMetricsDto()
)

data class TariffStatDto(
    val tariffName: String,
    val orderCount: Long,
    val totalRevenue: Double,
    val cancelledCount: Long = 0L,
    val avgCheck: Double = 0.0,
    val totalCommission: Double = 0.0,
    val totalDiscounts: Double = 0.0,
    val netRevenue: Double = 0.0
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

// Операционные KPI
data class OperationalKpiDto(
    val fulfillmentRate: Double,          // % вывозимости
    val avgTimeToAcceptSeconds: Double,   // Среднее время взятия водителем без предзаказов (сек)
    val avgTimeToArriveSeconds: Double = 0.0, // Среднее время подачи авто от принятия до прибытия (сек)
    val boostOrdersPercent: Double,       // % заказов с нажатием +20/+40 грн
    val boostFulfillmentPercent: Double,  // % вывозимости заказов после буста
    val quickClientCancelPercent: Double, // % отмен клиентом в первые 60 сек
    val timeoutCancelPercent: Double      // % реальных отмен по таймауту поиска (>180 сек без водителя)
)

// Распределение вывоза: Свой автопарк vs Партнерская биржа EvoS
data class FleetSplitDto(
    val ownFleetOrders: Long = 0L,
    val evosOrders: Long = 0L,
    val ownFleetSharePercent: Double = 0.0,
    val evosSharePercent: Double = 0.0,
    val chainOrdersCount: Long = 0L,
    val autoFilterOrdersCount: Long = 0L,
    val etherOrdersCount: Long = 0L
)

// Тайминги и SLA сервиса
data class SlaMetricsDto(
    val avgTimeToAcceptSeconds: Double = 0.0,     // Поиск водителя (сек)
    val avgTimeToArriveSeconds: Double = 0.0,     // Подача автомобиля (сек)
    val avgTripDurationSeconds: Double = 0.0,     // Длительность поездки (сек)
    val avgTripDistanceKm: Double = 0.0,          // Средняя дистанция (км)
    val scheduledOrdersCount: Long = 0L,          // Всего предзаказов за период
    val scheduledFulfillmentRate: Double = 0.0    // % выполнения предзаказов
)

// Полная 360° аналитика отмен
data class CancellationBreakdownDto(
    val totalCancelled: Long = 0L,
    val cancelledBeforeAccept: Long = 0L,         // Отменено до назначения водителя (в поиске)
    val cancelledAfterAccept: Long = 0L,          // Отменено после назначения водителя (срыв подачи)
    val cancelledByPartnerEvos: Long = 0L,        // Отменено партнерской службой EvoS
    val cancelledByDispatcher: Long = 0L,         // Отменено диспетчером
    val quickCancelUnder60s: Long = 0L,           // Быстрые отмены (< 60 сек)
    val timeoutCancelOver180s: Long = 0L          // Долгий безуспешный поиск (> 180 сек)
)

// Почасовой спрос и вывозимость (0..23)
data class HourlyDemandDto(
    val hour: Int,
    val totalOrders: Long,
    val completedOrders: Long,
    val cancelledOrders: Long,
    val fulfillmentRate: Double,
    val boostOrders: Long
)

// География спроса и невывоза по секторам / районам
data class SectorDemandDto(
    val sectorName: String,
    val totalOrders: Long,
    val completedOrders: Long,
    val cancelledOrders: Long,
    val fulfillmentRate: Double,
    val avgPrice: Double
)

data class CohortRetentionDto(
    val cohortWeek: String,               // Дата старта когорты по ISO-8601 (YYYY-Www)
    val totalUsers: Long,                 // Размер когорты
    val ride2RetentionPercent: Double,    // % совершивших 2-ю поездку (цель >= 30%)
    val ride3RetentionPercent: Double,    // % совершивших 3-ю поездку (цель >= 20%)
    val ride5PlusRetentionPercent: Double // % постоянных клиентов (5+ поездок)
)

data class PaybackPeriodDto(
    val estimatedCac: Double,             // Расчетный CAC (из настроек или 120 грн)
    val avgCommissionPerOrder: Double,    // Реальная средняя комиссия сервиса с поездки
    val targetRidesForPayback: Int,       // Целевое кол-во поездок для окупаемости CAC
    val avgDaysToPayback: Double          // Среднее число дней до окупаемости
)

data class FraudMetricDto(
    val blockedPromoAttempts: Long,       // Заблокированные попытки повторного использования promo
    val suspiciousDevicesCount: Long      // Кол-во устройств с >1 разными аккаунтами (мультиаккаунты)
)

data class DeepAnalyticsResponse(
    val general: GeneralAnalyticsResponse,
    val kpis: OperationalKpiDto,
    val cohorts: List<CohortRetentionDto>,
    val payback: PaybackPeriodDto,
    val fraud: FraudMetricDto,
    val unitEconomics: UnitEconomicsDto = UnitEconomicsDto(),
    val fleetSplit: FleetSplitDto = FleetSplitDto(),
    val sla: SlaMetricsDto = SlaMetricsDto(),
    val cancellationBreakdown: CancellationBreakdownDto = CancellationBreakdownDto(),
    val hourlyDemand: List<HourlyDemandDto> = emptyList(),
    val sectorDemand: List<SectorDemandDto> = emptyList(),
    val period: String = "ALL",
    val periodStart: String? = null,
    val periodEnd: String? = null
)