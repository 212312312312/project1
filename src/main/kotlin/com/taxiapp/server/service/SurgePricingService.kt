package com.taxiapp.server.service

import com.taxiapp.server.model.surge.TimeSurgeRule
import com.taxiapp.server.repository.TimeSurgeRuleRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.max
import kotlin.math.round

@Service
class SurgePricingService(
    private val timeRuleRepository: TimeSurgeRuleRepository,
    private val weatherService: OpenMeteoWeatherService
) {
    private val logger = LoggerFactory.getLogger(SurgePricingService::class.java)

    fun calculateCurrentSurgeMultiplier(lat: Double? = null, lng: Double? = null): Double {
        val now = LocalDateTime.now()
        val currentTime = now.toLocalTime()

        // 1. Поиск правил часов пик
        val activeTimeRules = timeRuleRepository.findAllByIsActiveTrue()
        val matchingRules = activeTimeRules.filter { rule -> isRuleActiveAt(rule, now) }

        val bestRule = matchingRules.maxByOrNull { it.multiplier }
        val timeMultiplier = bestRule?.multiplier ?: 1.0

        // Логирование часа пик по образцу погоды
        val dayUa = getDayOfWeekUkrainian(now.dayOfWeek)
        val timeStr = String.format("%02d:%02d", now.hour, now.minute)

        if (bestRule != null && timeMultiplier > 1.0) {
            logger.info("[PEAK_HOUR] Час: $timeStr $dayUa | Активне правило: '${bestRule.name}' (${bestRule.startTime}-${bestRule.endTime}) | Коефіцієнт: ${String.format(java.util.Locale.US, "%.2f", timeMultiplier)}")
        } else {
            logger.info("[PEAK_HOUR] Час: $timeStr $dayUa | Немає активних правил (активних у БД: ${activeTimeRules.size}) | Коефіцієнт: 1.00")
        }

        // 2. Коэффициент погоды
        val weatherStatus = weatherService.getCurrentWeather(lat, lng)
        val weatherMultiplier = weatherStatus.activeMultiplier

        // 3. Аддитивная сборка Surge: 1.0 + (Weather - 1.0) + (Peak - 1.0)
        val weatherSurplus = max(0.0, weatherMultiplier - 1.0)
        val timeSurplus = max(0.0, timeMultiplier - 1.0)
        val totalSurge = 1.0 + weatherSurplus + timeSurplus

        // Округляем до 2 знаков после запятой, минимум 1.0
        return max(1.0, round(totalSurge * 100.0) / 100.0)
    }

    /**
     * Проверка активности правила с учетом ночных смен (переход через 00:00)
     */
    private fun isRuleActiveAt(rule: TimeSurgeRule, now: LocalDateTime): Boolean {
        val currentDay = now.dayOfWeek
        val currentTime = now.toLocalTime()
        val start = rule.startTime
        val end = rule.endTime

        // Граница суток: если в UI указали 00:00, интерпретируем как конец суток 23:59:59.999
        val isMidnightEnd = end == LocalTime.MIDNIGHT || (end.hour == 0 && end.minute == 0)

        return if (isMidnightEnd) {
            // Например: 22:00 - 00:00 (до конца текущего дня)
            rule.daysOfWeek.contains(currentDay) && !currentTime.isBefore(start)
        } else if (end.isAfter(start)) {
            // Дневной интервал: например 07:30 - 10:00
            rule.daysOfWeek.contains(currentDay) && !currentTime.isBefore(start) && !currentTime.isAfter(end)
        } else {
            // Ночной интервал с переходом на следующий день: например 22:00 - 04:00
            if (!currentTime.isBefore(start)) {
                // До полуночи (вечер текущего дня)
                rule.daysOfWeek.contains(currentDay)
            } else if (!currentTime.isAfter(end)) {
                // После полуночи (утро следующего дня -> относим к дню начала смены)
                val previousDay = now.minusDays(1).dayOfWeek
                rule.daysOfWeek.contains(previousDay)
            } else {
                false
            }
        }
    }

    private fun getDayOfWeekUkrainian(day: DayOfWeek): String = when (day) {
        DayOfWeek.MONDAY -> "Пн"
        DayOfWeek.TUESDAY -> "Вт"
        DayOfWeek.WEDNESDAY -> "Ср"
        DayOfWeek.THURSDAY -> "Чт"
        DayOfWeek.FRIDAY -> "Пт"
        DayOfWeek.SATURDAY -> "Сб"
        DayOfWeek.SUNDAY -> "Нд"
    }
}