package com.taxiapp.server.dto.surge

import java.time.DayOfWeek

data class TimeSurgeRuleDto(
    val id: Long? = null,
    val name: String,
    val daysOfWeek: Set<DayOfWeek>,
    val startTime: String, // "08:00"
    val endTime: String,   // "10:00"
    val multiplier: Double,
    val isActive: Boolean
)

data class WeatherSurgeRuleDto(
    val id: Long? = null,
    val weatherType: String,
    val name: String,
    val multiplier: Double,
    val isActive: Boolean
)

data class WeatherStatusDto(
    val isEnabled: Boolean,
    val currentTemperature: Double?,
    val weatherCode: Int?,
    val weatherType: String,
    val weatherDescription: String,
    val activeMultiplier: Double,
    val location: String = "Київ"
)

data class SurgeConfigDto(
    val weatherSurgeEnabled: Boolean,
    val timeRules: List<TimeSurgeRuleDto>,
    val weatherRules: List<WeatherSurgeRuleDto>,
    val currentWeather: WeatherStatusDto
)