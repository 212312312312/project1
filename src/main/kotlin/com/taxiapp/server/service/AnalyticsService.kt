package com.taxiapp.server.service

import com.taxiapp.server.dto.analytics.*
import com.taxiapp.server.model.analytics.ClientAppAction
import com.taxiapp.server.model.analytics.ClientAppEvent
import com.taxiapp.server.model.enums.OrderStatus
import com.taxiapp.server.repository.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class AnalyticsService(
    private val taxiOrderRepository: TaxiOrderRepository,
    private val clientRepository: ClientRepository,
    private val clientAppEventRepository: ClientAppEventRepository,
    private val clientAppActionRepository: ClientAppActionRepository
) {

    // Кэш аналитики на 60 секунд (защищает БД от десятков тяжелых GROUP BY / COUNT запросов при обновлении страницы в админке)
    @Volatile
    private var cachedGeneralAnalytics: GeneralAnalyticsResponse? = null

    @Volatile
    private var generalCacheExpiresAtMs: Long = 0L

    @Volatile
    private var cachedDeepAnalytics: DeepAnalyticsResponse? = null

    @Volatile
    private var deepCacheExpiresAtMs: Long = 0L

    @Transactional(readOnly = true)
    fun getGeneralAnalytics(): GeneralAnalyticsResponse {
        val nowMs = System.currentTimeMillis()
        cachedGeneralAnalytics?.let {
            if (nowMs < generalCacheExpiresAtMs) return it
        }

        val averageOrderValue = taxiOrderRepository.calculateAverageOrderValue() ?: 0.0
        val totalLtvSum = taxiOrderRepository.calculateTotalRevenue() ?: 0.0

        val totalClientsCount = clientRepository.count()
        val uniqueClientsWithOrders = taxiOrderRepository.countUniqueClientsWithOrders()

        val conversionRate = if (totalClientsCount > 0) {
            (uniqueClientsWithOrders.toDouble() / totalClientsCount.toDouble()) * 100.0
        } else 0.0

        val totalOrders = taxiOrderRepository.count()
        val completedOrders = taxiOrderRepository.countByStatus(OrderStatus.COMPLETED)

        val fulfillmentRate = if (totalOrders > 0) {
            Math.round((completedOrders.toDouble() / totalOrders.toDouble() * 100.0) * 10.0) / 10.0
        } else 0.0

        val averageLtv = if (uniqueClientsWithOrders > 0) {
            totalLtvSum / uniqueClientsWithOrders
        } else 0.0

        val tariffStats = taxiOrderRepository.getTariffAnalytics().map { row ->
            TariffStatDto(
                tariffName = row[0] as? String ?: "Невідомий тариф",
                orderCount = row[1] as Long,
                totalRevenue = row[2] as Double
            )
        }

        val screenStats = clientAppEventRepository.getScreenStats().map { row ->
            val avgSeconds = row[2] as? Double ?: 0.0
            ScreenStatDto(
                screenName = row[0] as String,
                visitCount = row[1] as Long,
                averageDurationSeconds = Math.round(avgSeconds * 10.0) / 10.0
            )
        }

        val trafficStats = clientRepository.getTrafficSourceStats().map { row ->
            val rawSource = row[0] as? String
            TrafficSourceStatDto(
                source = rawSource ?: "Органічний трафік (Пряме встановлення)",
                medium = row[1] as? String ?: (if (rawSource == null) "organic" else "not_set"),
                campaign = row[2] as? String ?: (if (rawSource == null) "Прямий візит" else "not_set"),
                userCount = row[3] as Long
            )
        }

        val actionStats = clientAppActionRepository.getActionStats().map { row ->
            ActionStatDto(
                actionName = row[0] as String,
                actionValue = row[1] as? String,
                count = row[2] as Long
            )
        }

        val clientCancellationsRaw = taxiOrderRepository.getClientCancellationStats()
        val totalClientCancellations = clientCancellationsRaw.sumOf { it.count }.toDouble()

        val clientCancellationStats = clientCancellationsRaw.map { row ->
            val count = row.count
            val percentage = if (totalClientCancellations > 0) {
                Math.round((count.toDouble() / totalClientCancellations * 100.0) * 10.0) / 10.0
            } else 0.0
            ClientCancellationStatDto(
                reason = row.reason,
                count = count,
                percentage = percentage
            )
        }

        val response = GeneralAnalyticsResponse(
            averageOrderValue = averageOrderValue,
            totalLtvSum = totalLtvSum,
            averageLtv = averageLtv,
            conversionRate = conversionRate,
            fulfillmentRate = fulfillmentRate,
            tariffStats = tariffStats,
            screenStats = screenStats,
            trafficStats = trafficStats,
            actionStats = actionStats,
            clientCancellationStats = clientCancellationStats
        )

        cachedGeneralAnalytics = response
        generalCacheExpiresAtMs = nowMs + 60_000L
        return response
    }

    @Transactional(readOnly = true)
    fun getDeepAnalytics(): DeepAnalyticsResponse {
        val nowMs = System.currentTimeMillis()
        cachedDeepAnalytics?.let {
            if (nowMs < deepCacheExpiresAtMs) return it
        }

        val general = getGeneralAnalytics()
        val totalOrders = taxiOrderRepository.count()

        val avgTimeToAccept = taxiOrderRepository.calculateAvgTimeToAcceptSeconds()
        val boostOrdersCount = taxiOrderRepository.countOrdersWithBoost()
        val boostCompletedCount = taxiOrderRepository.countCompletedOrdersWithBoost()
        val quickCancels = taxiOrderRepository.countQuickClientCancellations()
        val timeoutCancels = taxiOrderRepository.countTimeoutCancellations()

        val kpis = OperationalKpiDto(
            fulfillmentRate = general.fulfillmentRate,
            avgTimeToAcceptSeconds = Math.round(avgTimeToAccept * 10.0) / 10.0,
            boostOrdersPercent = if (totalOrders > 0) Math.round((boostOrdersCount.toDouble() / totalOrders * 100.0) * 10.0) / 10.0 else 0.0,
            boostFulfillmentPercent = if (boostOrdersCount > 0) Math.round((boostCompletedCount.toDouble() / boostOrdersCount * 100.0) * 10.0) / 10.0 else 0.0,
            quickClientCancelPercent = if (totalOrders > 0) Math.round((quickCancels.toDouble() / totalOrders * 100.0) * 10.0) / 10.0 else 0.0,
            timeoutCancelPercent = if (totalOrders > 0) Math.round((timeoutCancels.toDouble() / totalOrders * 100.0) * 10.0) / 10.0 else 0.0
        )

        // HIGH-LOAD ФИКС: Вместо clientRepository.findAll() (выгружающего 100k полных JPA-сущностей Client)
        // используем легковесную проекцию из 2 колонок: registrationDatetime и totalCompletedOrders
        val cohortRawRows = clientRepository.findCohortRawData()
        val cohorts = cohortRawRows
            .mapNotNull { row ->
                val dt = row.getOrNull(0) as? LocalDateTime ?: return@mapNotNull null
                val completedCount = (row.getOrNull(1) as? Number)?.toInt() ?: 0
                val cohortWeek = "${dt.year}-W${String.format("%02d", (dt.dayOfYear / 7) + 1)}"
                Pair(cohortWeek, completedCount)
            }
            .groupBy({ it.first }, { it.second })
            .map { (cohortWeek, completedCounts) ->
                val size = completedCounts.size.toDouble()
                val ride2Count = completedCounts.count { it >= 2 }
                val ride3Count = completedCounts.count { it >= 3 }
                val ride5Count = completedCounts.count { it >= 5 }

                CohortRetentionDto(
                    cohortWeek = cohortWeek,
                    totalUsers = completedCounts.size.toLong(),
                    ride2RetentionPercent = if (size > 0) Math.round((ride2Count / size * 100.0) * 10.0) / 10.0 else 0.0,
                    ride3RetentionPercent = if (size > 0) Math.round((ride3Count / size * 100.0) * 10.0) / 10.0 else 0.0,
                    ride5PlusRetentionPercent = if (size > 0) Math.round((ride5Count / size * 100.0) * 10.0) / 10.0 else 0.0
                )
            }
            .sortedByDescending { it.cohortWeek }

        val avgDaysTo8th = taxiOrderRepository.calculateAvgDaysTo8thRide()
        val payback = PaybackPeriodDto(
            estimatedCac = 120.0,
            avgCommissionPerOrder = 15.0,
            targetRidesForPayback = 8,
            avgDaysToPayback = Math.round(avgDaysTo8th * 10.0) / 10.0
        )

        val fraud = FraudMetricDto(
            blockedPromoAttempts = 0L,
            suspiciousDevicesCount = 0L
        )

        val response = DeepAnalyticsResponse(
            general = general,
            kpis = kpis,
            cohorts = cohorts,
            payback = payback,
            fraud = fraud
        )

        cachedDeepAnalytics = response
        deepCacheExpiresAtMs = nowMs + 60_000L
        return response
    }

    @Transactional
    fun saveClientEvents(username: String, request: ClientEventBatchRequest) {
        val client = if (username.isNotBlank()) {
            clientRepository.findByUserPhone(username).orElseGet {
                clientRepository.findByUserLogin(username).orElseGet {
                    clientRepository.findByEmail(username).orElse(null)
                }
            }
        } else null

        if (client == null) {
            return
        }

        if (client.utmSource == null && request.utmSource != null) {
            client.utmSource = request.utmSource
            client.utmMedium = request.utmMedium
            client.utmCampaign = request.utmCampaign
            clientRepository.save(client)
        }

        val entities = request.events.map { eventDto ->
            ClientAppEvent(
                clientId = client.id!!,
                screenName = eventDto.screenName,
                sessionId = request.sessionId,
                durationSeconds = eventDto.durationSeconds,
                createdAt = LocalDateTime.now()
            )
        }
        clientAppEventRepository.saveAll(entities)

        if (!request.customEvents.isNullOrEmpty()) {
            val actionEntities = request.customEvents.map { actionDto ->
                ClientAppAction(
                    clientId = client.id!!,
                    actionName = actionDto.eventName,
                    actionValue = actionDto.eventValue,
                    sessionId = request.sessionId,
                    createdAt = LocalDateTime.now()
                )
            }
            clientAppActionRepository.saveAll(actionEntities)
        }
    }
}