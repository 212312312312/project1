package com.taxiapp.server.model.surge

import jakarta.persistence.*

@Entity
@Table(name = "weather_surge_rules")
data class WeatherSurgeRule(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false, unique = true)
    var weatherType: String, // RAIN, HEAVY_RAIN, SNOW, THUNDERSTORM, FOG

    @Column(nullable = false)
    var name: String, // "Дощ", "Злива", "Снігопад", "Гроза", "Туман"

    @Column(nullable = false)
    var multiplier: Double = 1.0,

    @Column(nullable = false)
    var isActive: Boolean = true
)