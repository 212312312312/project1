package com.taxiapp.server.service

import com.taxiapp.server.dto.analytics.*
import com.taxiapp.server.model.analytics.ClientAppEvent
import com.taxiapp.server.repository.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import com.taxiapp.server.model.analytics.ClientAppAction

@Service
class AnalyticsService(
    private val taxiOrderRepository: TaxiOrderRepository,
    private val clientRepository: ClientRepository,
    private val clientAppEventRepository: ClientAppEventRepository,
    private val clientAppActionRepository: ClientAppActionRepository // Добавили новый репо
) {

    @Transactional(readOnly = true)
    fun getGeneralAnalytics(): GeneralAnalyticsResponse {
        val averageOrderValue = taxiOrderRepository.calculateAverageOrderValue() ?: 0.0
        val totalLtvSum = taxiOrderRepository.calculateTotalRevenue() ?: 0.0
        
        val totalClientsCount = clientRepository.count()
        val uniqueClientsWithOrders = taxiOrderRepository.countUniqueClientsWithOrders()

        // 4. Расчет конверсии (процент зарегистрированных, сделавших хотя бы 1 успешный заказ)
        val conversionRate = if (totalClientsCount > 0) {
            (uniqueClientsWithOrders.toDouble() / totalClientsCount.toDouble()) * 100.0
        } else 0.0

        val totalOrders = taxiOrderRepository.count()
        val completedOrders = taxiOrderRepository.countByStatus(com.taxiapp.server.model.enums.OrderStatus.COMPLETED)
        
        val fulfillmentRate = if (totalOrders > 0) {
            Math.round((completedOrders.toDouble() / totalOrders.toDouble() * 100.0) * 10.0) / 10.0
        } else 0.0

        // 3. Средний LTV на одного платящего клиента
        val averageLtv = if (uniqueClientsWithOrders > 0) {
            totalLtvSum / uniqueClientsWithOrders
        } else 0.0

        // 6. Товарная (тарифная) аналитика
        val tariffStats = taxiOrderRepository.getTariffAnalytics().map { row ->
            TariffStatDto(
                tariffName = row[0] as? String ?: "Невідомий тариф",
                orderCount = row[1] as Long,
                totalRevenue = row[2] as Double
            )
        }

        // 1. Среднее время и статистика по экранам дропа
        val screenStats = clientAppEventRepository.getScreenStats().map { row ->
            val avgSeconds = row[2] as? Double ?: 0.0
            ScreenStatDto(
                screenName = row[0] as String,
                visitCount = row[1] as Long,
                averageDurationSeconds = Math.round(avgSeconds * 10.0) / 10.0
            )
        }

        // 2. Джерела трафіку (UTM + Маркетингова атрибуція)
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

        // 2. ОБНОВИ return БЛОК (добавь поле в самый конец аргументов):
        return GeneralAnalyticsResponse(
            averageOrderValue = averageOrderValue,
            totalLtvSum = totalLtvSum,
            averageLtv = averageLtv,
            conversionRate = conversionRate,
            fulfillmentRate = fulfillmentRate, // <-- ПЕРЕДАЕМ СЮДА
            tariffStats = tariffStats,
            screenStats = screenStats,
            trafficStats = trafficStats,
            actionStats = actionStats,
            clientCancellationStats = clientCancellationStats
        )
    }

    // ➕ Добавить расчет глубокой аналитики:
@Transactional(readOnly = true)
fun getDeepAnalytics(): DeepAnalyticsResponse {
    val general = getGeneralAnalytics()
    val totalOrders = taxiOrderRepository.count()
    
    // 1. Операционные KPIs
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

    // 2. Когортный анализ (Недельные когорты по дате регистрации)
    val allClients = clientRepository.findAll()
    val cohorts = allClients
        .groupBy { client ->
            val dt = client.registrationDatetime
            "${dt.year}-W${String.format("%02d", (dt.dayOfYear / 7) + 1)}"
        }
        .map { (cohortWeek, clientsInCohort) ->
            val size = clientsInCohort.size.toDouble()
            val ride2Count = clientsInCohort.count { it.totalCompletedOrders >= 2 }
            val ride3Count = clientsInCohort.count { it.totalCompletedOrders >= 3 }
            val ride5Count = clientsInCohort.count { it.totalCompletedOrders >= 5 }
            
            CohortRetentionDto(
                cohortWeek = cohortWeek,
                totalUsers = clientsInCohort.size.toLong(),
                ride2RetentionPercent = if (size > 0) Math.round((ride2Count / size * 100.0) * 10.0) / 10.0 else 0.0,
                ride3RetentionPercent = if (size > 0) Math.round((ride3Count / size * 100.0) * 10.0) / 10.0 else 0.0,
                ride5PlusRetentionPercent = if (size > 0) Math.round((ride5Count / size * 100.0) * 10.0) / 10.0 else 0.0
            )
        }
        .sortedByDescending { it.cohortWeek }

    // 3. Срок окупаемости (Payback Period при CAC = 120 грн и комиссии = 15 грн)
    val avgDaysTo8th = taxiOrderRepository.calculateAvgDaysTo8thRide()
    val payback = PaybackPeriodDto(
        estimatedCac = 120.0,
        avgCommissionPerOrder = 15.0,
        targetRidesForPayback = 8,
        avgDaysToPayback = Math.round(avgDaysTo8th * 10.0) / 10.0
    )

    // 4. Антифрод метрики
    val fraud = FraudMetricDto(
        blockedPromoAttempts = 0L, // Счетчик из Redis или логов блокировок
        suspiciousDevicesCount = 0L
    )

    return DeepAnalyticsResponse(
        general = general,
        kpis = kpis,
        cohorts = cohorts,
        payback = payback,
        fraud = fraud
    )
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
            // Запрос от неавторизованного пользователя
            return
        }

        // Сохраняем UTM-метки (First-Touch Attribution)
        if (client.utmSource == null && request.utmSource != null) {
            client.utmSource = request.utmSource
            client.utmMedium = request.utmMedium
            client.utmCampaign = request.utmCampaign
            clientRepository.save(client)
        }

        // Пакетное сохранение событий экранов
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

        // Пакетное сохранение кастомных действий (кликов)
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