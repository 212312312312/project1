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

        return GeneralAnalyticsResponse(
            averageOrderValue = averageOrderValue,
            totalLtvSum = totalLtvSum,
            averageLtv = averageLtv,
            conversionRate = conversionRate,
            tariffStats = tariffStats,
            screenStats = screenStats,
            trafficStats = trafficStats,
            actionStats = actionStats
        )
    }

    @Transactional
    fun saveClientEvents(username: String, request: ClientEventBatchRequest) {
        val client = clientRepository.findByUserPhone(username)
            .orElseThrow { IllegalArgumentException("Клієнта не знайдено") }

        // Сохраняем UTM-метки только если они еще не были установлены (First-Touch Attribution)
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

        // Пакетное сохранение кастомных действий (кликов) из приложения
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