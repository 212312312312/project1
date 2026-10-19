package com.taxiapp.server.service

import com.taxiapp.server.model.surge.TimeSurgeRule
import com.taxiapp.server.repository.TimeSurgeRuleRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale
import kotlin.math.max
import kotlin.math.round

@Service
class SurgePricingService(
    private val timeRuleRepository: TimeSurgeRuleRepository,
    private val weatherService: OpenMeteoWeatherService
) {
    private val logger = LoggerFactory.getLogger(SurgePricingService::class.java)

    // L1 In-Memory кэш активных правил часов пик (TTL 30 секунд)
    // Избавляет PostgreSQL от 5-6 одинаковых SQL-запросов на каждый просчет маршрута клиентом
    @Volatile
    private var cachedActiveRules: List<TimeSurgeRule> = emptyList()

    @Volatile
    private var rulesCacheExpiresAtMs: Long = 0L

    fun invalidateCache() {
        rulesCacheExpiresAtMs = 0L
    }

    private fun getActiveTimeRulesCached(): List<TimeSurgeRule> {
        val nowMs = System.currentTimeMillis()
        if (nowMs < rulesCacheExpiresAtMs) {
            return cachedActiveRules
        }
        val freshRules = timeRuleRepository.findAllByIsActiveTrue()
        cachedActiveRules = freshRules
        rulesCacheExpiresAtMs = nowMs + 30_000L
        return freshRules
    }

    fun calculateCurrentSurgeMultiplier(lat: Double? = null, lng: Double? = null): Double {
        val now = LocalDateTime.now()

        // 1. Поиск правил часов пик из быстрого кэша в памяти
        val activeTimeRules = getActiveTimeRulesCached()
        val matchingRules = activeTimeRules.filter { rule -> isRuleActiveAt(rule, now) }

        val bestRule = matchingRules.maxByOrNull { it.multiplier }
        val timeMultiplier = bestRule?.multiplier ?: 1.0

        if (logger.isDebugEnabled) {
            val dayUa = getDayOfWeekUkrainian(now.dayOfWeek)
            val timeStr = String.format("%02d:%02d", now.hour, now.minute)
            if (bestRule != null && timeMultiplier > 1.0) {
                logger.debug("[PEAK_HOUR] Час: $timeStr $dayUa | Активне правило: '${bestRule.name}' (${bestRule.startTime}-${bestRule.endTime}) | Коефіцієнт: ${String.format(Locale.US, "%.2f", timeMultiplier)}")
            } else {
                logger.debug("[PEAK_HOUR] Час: $timeStr $dayUa | Немає активних правил (активних у БД: ${activeTimeRules.size}) | Коефіцієнт: 1.00")
            }
        }

        // 2. Коэффициент погоды
        val weatherStatus = weatherService.getCurrentWeather(lat, lng)
        val weatherMultiplier = weatherStatus.activeMultiplier

        // 3. Аддитивная сборка Surge: 1.0 + (Weather - 1.0) + (Peak - 1.0)
        val weatherSurplus = max(0.0, weatherMultiplier - 1.0)
        val timeSurplus = max(0.0, timeMultiplier - 1.0)
        val totalSurge = 1.0 + weatherSurplus + timeSurplus

        return max(1.0, round(totalSurge * 100.0) / 100.0)
    }

    private fun isRuleActiveAt(rule: TimeSurgeRule, now: LocalDateTime): Boolean {
        val currentDay = now.dayOfWeek
        val currentTime = now.toLocalTime()
        val start = rule.startTime
        val end = rule.endTime

        val isMidnightEnd = end == LocalTime.MIDNIGHT || (end.hour == 0 && end.minute == 0)

        return if (isMidnightEnd) {
            rule.daysOfWeek.contains(currentDay) && !currentTime.isBefore(start)
        } else if (end.isAfter(start)) {
            rule.daysOfWeek.contains(currentDay) && !currentTime.isBefore(start) && !currentTime.isAfter(end)
        } else {
            if (!currentTime.isBefore(start)) {
                rule.daysOfWeek.contains(currentDay)
            } else if (!currentTime.isAfter(end)) {
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