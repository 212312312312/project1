package com.taxiapp.server.service

import com.taxiapp.server.dto.driver.ActivityHistoryItemDto
import com.taxiapp.server.dto.driver.ActivityLevel
import com.taxiapp.server.dto.driver.DriverActivityDto
import com.taxiapp.server.model.driver.DriverActivityHistory
import com.taxiapp.server.model.order.TaxiOrder
import com.taxiapp.server.model.user.Driver
import com.taxiapp.server.repository.DriverActivityHistoryRepository
import com.taxiapp.server.repository.DriverRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class DriverActivityService(
    private val driverRepository: DriverRepository,
    private val historyRepository: DriverActivityHistoryRepository
) {

    @Transactional(readOnly = true)
    fun getDriverActivity(driver: Driver): DriverActivityDto {
        val history = historyRepository.findTop50ByDriverIdOrderByCreatedAtDesc(driver.id!!)
            .map { ActivityHistoryItemDto(it.pointsChange, it.reason, it.createdAt) }

        return DriverActivityDto(
            score = driver.activityScore,
            level = calculateLevel(driver.activityScore),
            history = history
        )
    }

    @Transactional
    fun processOrderCompletion(driver: Driver, order: TaxiOrder) {
        // 1. Базовые баллы на основе типа распределения заказа
        var pointsToAdd = when (order.assignmentType) {
            "CHAIN" -> 6
            "HOME" -> 6
            "CYCLE" -> 5
            "AUTO" -> 4
            else -> 3 // "ETHER" или null
        }
        
        val reasons = mutableListOf<String>()
        reasons.add(when (order.assignmentType) {
            "CHAIN" -> "Ланцюг замовлень"
            "HOME" -> "Попутно додому"
            "CYCLE" -> "Режим Цикл"
            "AUTO" -> "Автопризначення"
            else -> "Ефір"
        })

        // 2. Дополнительные баллы за условия поездки
        if (order.originSector?.isCity == false || order.destinationSector?.isCity == false) {
            pointsToAdd += 3
            reasons.add("За місто")
        }

        if (order.stops.isNotEmpty()) {
            pointsToAdd += 3
            reasons.add("Проміжні точки")
        }

        if (order.scheduledAt != null) {
            pointsToAdd += 3
            reasons.add("Заплановане замовлення")
        }

        if (order.paymentMethod == "CARD") {
            pointsToAdd += 1
            reasons.add("Оплата на баланс")
        }

        // Собираем красивую детальную строчку для истории (например: "Замовлення #15: Ефір, За місто, Оплата на баланс")
        val reasonString = "Замовлення #${order.id}: " + reasons.joinToString(", ")
        updateScore(driver, pointsToAdd, reasonString, order.id)
    }

    // --- ОБНОВЛЕНО: Принимаем размер штрафа и текст причины ---
    @Transactional
    fun processOrderCancellation(driver: Driver, orderId: Long, penalty: Int, reasonText: String) {
        // penalty должен приходить положительным числом (например, 50), здесь мы делаем его отрицательным
        val pointsChange = -penalty
        val finalReason = "Скасування (#$orderId): $reasonText"
        
        updateScore(driver, pointsChange, finalReason, orderId)
    }
    // -----------------------------------------------------------

    @Transactional 
    fun updateScore(driver: Driver, change: Int, reason: String, orderId: Long? = null) {
        val newScore = (driver.activityScore + change).coerceIn(0, 1000)
        
        driver.activityScore = newScore
        driverRepository.save(driver)

        val history = DriverActivityHistory(
            driver = driver,
            pointsChange = change,
            reason = reason,
            orderId = orderId
        )
        historyRepository.save(history)
    }

    private fun calculateLevel(score: Int): ActivityLevel {
        return when (score) {
            in 701..1000 -> ActivityLevel.GREEN
            in 401..700 -> ActivityLevel.YELLOW
            in 1..400 -> ActivityLevel.RED
            else -> ActivityLevel.BLOCKED
        }
    }
}