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
        var pointsToAdd = 0
        val reasons = mutableListOf<String>()

        pointsToAdd += 2
        reasons.add("Ефір")

        if (order.stops.isNotEmpty()) {
            pointsToAdd += 3
            reasons.add("Проміжні точки")
        }

        if (order.paymentMethod == "CARD") {
            pointsToAdd += 1
            reasons.add("Оплата карткою")
        }

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