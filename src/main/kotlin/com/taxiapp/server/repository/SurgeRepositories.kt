package com.taxiapp.server.repository

import com.taxiapp.server.model.surge.TimeSurgeRule
import com.taxiapp.server.model.surge.WeatherSurgeRule
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface TimeSurgeRuleRepository : JpaRepository<TimeSurgeRule, Long> {
    fun findAllByIsActiveTrue(): List<TimeSurgeRule>
}

@Repository
interface WeatherSurgeRuleRepository : JpaRepository<WeatherSurgeRule, Long> {
    fun findAllByIsActiveTrue(): List<WeatherSurgeRule>
    fun findByWeatherType(weatherType: String): WeatherSurgeRule?
}