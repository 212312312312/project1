package com.taxiapp.server.service

import com.google.gson.JsonParser
import com.taxiapp.server.dto.surge.WeatherStatusDto
import com.taxiapp.server.model.surge.WeatherSurgeRule
import com.taxiapp.server.repository.AppSettingRepository
import com.taxiapp.server.repository.WeatherSurgeRuleRepository
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.math.round

@Service
class OpenMeteoWeatherService(
    private val appSettingRepository: AppSettingRepository,
    private val weatherRuleRepository: WeatherSurgeRuleRepository,
    private val redisTemplate: RedisTemplate<String, Any>
) {
    private val logger = LoggerFactory.getLogger(OpenMeteoWeatherService::class.java)

    private val DEFAULT_LAT = 50.4501
    private val DEFAULT_LNG = 30.5234

    // L1 In-Memory кэш для флага weather_surge_enabled и таблицы правил погоды (TTL 30 сек)
    // Убирает 2 SQL-запроса к БД на каждый тариф при каждом расчете цены поездки!
    @Volatile
    private var cachedEnabled: Boolean = true

    @Volatile
    private var enabledExpiresAtMs: Long = 0L

    @Volatile
    private var cachedRulesByType: Map<String, WeatherSurgeRule> = emptyMap()

    @Volatile
    private var rulesExpiresAtMs: Long = 0L

    fun isWeatherSurgeEnabled(): Boolean {
        val nowMs = System.currentTimeMillis()
        if (nowMs < enabledExpiresAtMs) {
            return cachedEnabled
        }
        val setting = appSettingRepository.findById("weather_surge_enabled").orElse(null)
        val enabled = setting?.value?.toBooleanStrictOrNull() ?: true
        cachedEnabled = enabled
        enabledExpiresAtMs = nowMs + 30_000L
        return enabled
    }

    fun setWeatherSurgeEnabled(enabled: Boolean) {
        val setting = appSettingRepository.findById("weather_surge_enabled")
            .orElseGet { com.taxiapp.server.model.setting.AppSetting("weather_surge_enabled", "true") }
        setting.value = enabled.toString()
        appSettingRepository.save(setting)
        cachedEnabled = enabled
        enabledExpiresAtMs = System.currentTimeMillis() + 30_000L
    }

    private fun getWeatherRuleCached(weatherType: String): WeatherSurgeRule? {
        val nowMs = System.currentTimeMillis()
        if (nowMs >= rulesExpiresAtMs || cachedRulesByType.isEmpty()) {
            val rules = weatherRuleRepository.findAll().associateBy { it.weatherType }
            cachedRulesByType = rules
            rulesExpiresAtMs = nowMs + 30_000L
        }
        return cachedRulesByType[weatherType]
    }

    fun getCurrentWeather(lat: Double? = null, lng: Double? = null): WeatherStatusDto {
        val isEnabled = isWeatherSurgeEnabled()
        val targetLat = lat ?: DEFAULT_LAT
        val targetLng = lng ?: DEFAULT_LNG

        val gridLat = round(targetLat * 100.0) / 100.0
        val gridLng = round(targetLng * 100.0) / 100.0
        val cacheKey = "weather:$gridLat:$gridLng"

        val cached = try {
            redisTemplate.opsForValue().get(cacheKey) as? Map<*, *>
        } catch (_: Exception) {
            null
        }

        val (temp, code) = if (cached != null) {
            val t = (cached["temp"] as? Number)?.toDouble()
            val c = (cached["code"] as? Number)?.toInt()
            Pair(t, c)
        } else {
            val fetched = fetchFromOpenMeteo(gridLat, gridLng)
            if (fetched != null) {
                try {
                    redisTemplate.opsForValue().set(
                        cacheKey,
                        mapOf("temp" to fetched.first, "code" to fetched.second),
                        10,
                        TimeUnit.MINUTES
                    )
                } catch (_: Exception) {}
            }
            fetched ?: Pair(null, 0)
        }

        val weatherType = mapWmoCodeToType(code ?: 0)
        val description = mapWmoCodeToDescription(code ?: 0)

        var multiplier = 1.0
        if (isEnabled && weatherType != "CLEAR") {
            val rule = getWeatherRuleCached(weatherType)
            if (rule != null && rule.isActive) {
                multiplier = rule.multiplier
            }
        }

        logger.debug("[WEATHER] Локація: $targetLat, $targetLng | Код WMO: $code ($description) | Тип: $weatherType | Коефіцієнт: $multiplier (Ввімкнено: $isEnabled)")

        return WeatherStatusDto(
            isEnabled = isEnabled,
            currentTemperature = temp,
            weatherCode = code,
            weatherType = weatherType,
            weatherDescription = description,
            activeMultiplier = multiplier,
            location = if (lat == null || (lat == DEFAULT_LAT && lng == DEFAULT_LNG)) "Київ" else "$gridLat, $gridLng"
        )
    }

    private fun fetchFromOpenMeteo(lat: Double, lng: Double): Pair<Double?, Int?>? {
        return try {
            val urlString = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lng&current=temperature_2m,weather_code,precipitation,rain,snowfall"
            val connection = URL(urlString).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 2500
            connection.readTimeout = 2500

            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val root = JsonParser.parseString(response).asJsonObject
                val current = root.getAsJsonObject("current")
                val temp = current.get("temperature_2m")?.asDouble
                var code = current.get("weather_code")?.asInt ?: 0
                val precipitation = current.get("precipitation")?.asDouble ?: 0.0
                val rain = current.get("rain")?.asDouble ?: 0.0
                val snowfall = current.get("snowfall")?.asDouble ?: 0.0

                if (code in 0..3) {
                    if (snowfall > 0.05) {
                        code = 71
                    } else if (rain > 0.05 || precipitation > 0.05) {
                        code = 51
                    }
                }

                Pair(temp, code)
            } else {
                logger.warn("Open-Meteo returned code: ${connection.responseCode}")
                null
            }
        } catch (e: Exception) {
            logger.error("Failed to fetch weather from Open-Meteo: ${e.message}")
            null
        }
    }

    private fun mapWmoCodeToType(code: Int): String {
        return when (code) {
            45, 48 -> "FOG"
            51, 53, 55, 56, 57, 61, 66, 80 -> "RAIN"
            63, 65, 67, 81, 82 -> "HEAVY_RAIN"
            71, 73, 75, 77, 85, 86 -> "SNOW"
            95, 96, 99 -> "THUNDERSTORM"
            else -> "CLEAR"
        }
    }

    private fun mapWmoCodeToDescription(code: Int): String {
        return when (code) {
            0 -> "Ясно ☀️"
            1, 2, 3 -> "Хмарно ⛅"
            45, 48 -> "Туман 🌫️"
            51, 53, 55, 56, 57 -> "Дрібний дощ"
            61, 66, 80 -> "Невеликий дощ 🌧️"
            63, 65, 67 -> "Сильний дощ"
            81, 82 -> "Злива"
            71, 73, 75, 77, 85, 86 -> "Снігопад"
            95, 96, 99 -> "Гроза"
            else -> "Без опадів"
        }
    }
}