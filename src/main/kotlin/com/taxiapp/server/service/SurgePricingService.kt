package com.taxiapp.server.service

import com.taxiapp.server.repository.TimeSurgeRuleRepository
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.max
import kotlin.math.round

@Service
class SurgePricingService(
    private val timeRuleRepository: TimeSurgeRuleRepository,
    private val weatherService: OpenMeteoWeatherService
) {
    fun calculateCurrentSurgeMultiplier(lat: Double? = null, lng: Double? = null): Double {
        val now = LocalDateTime.now()
        val currentDay = now.dayOfWeek
        val currentTime = now.toLocalTime()

        // 1. Коефіцієнт часу
        val activeTimeRules = timeRuleRepository.findAllByIsActiveTrue()
        val matchingTimeRules = activeTimeRules.filter { rule ->
            rule.daysOfWeek.contains(currentDay) && isTimeInRange(currentTime, rule.startTime, rule.endTime)
        }
        val timeMultiplier = matchingTimeRules.maxOfOrNull { it.multiplier } ?: 1.0

        // 2. Коефіцієнт погоди
        val weatherStatus = weatherService.getCurrentWeather(lat, lng)
        val weatherMultiplier = weatherStatus.activeMultiplier

        // 3. Підсумковий коефіцієнт (максимальний або комбінований)
        val combined = if (timeMultiplier > 1.0 && weatherMultiplier > 1.0) {
            round(timeMultiplier * weatherMultiplier * 100.0) / 100.0
        } else {
            max(timeMultiplier, weatherMultiplier)
        }

        return max(1.0, combined)
    }

    private fun isTimeInRange(current: LocalTime, start: LocalTime, end: LocalTime): Boolean {
        return if (end.isAfter(start)) {
            !current.isBefore(start) && !current.isAfter(end)
        } else {
            // Перехід через північ (наприклад, 23:00 - 04:00)
            !current.isBefore(start) || !current.isAfter(end)
        }
    }
}