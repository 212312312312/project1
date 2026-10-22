package com.taxiapp.server.service

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.taxiapp.server.dto.order.ReverseGeocodeResponseDto
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

@Service
class GeocodingService(
    private val redisTemplate: RedisTemplate<String, Any>,
    private val sectorService: SectorService
) {

    private val logger = LoggerFactory.getLogger(GeocodingService::class.java)

    /**
     * Серверный обратный геокодинг с кэшированием в Redis (точность 5 знаков = ~1.1 м, TTL 24 часа)
     * и безотказным фоллбэком на сектора агрегатора при недоступности внешнего геокодера.
     */
    fun reverseGeocode(lat: Double, lng: Double, lang: String = "uk"): ReverseGeocodeResponseDto {
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0 || (abs(lat) < 0.0001 && abs(lng) < 0.0001)) {
            return ReverseGeocodeResponseDto(address = "Точка на карті", source = "fallback")
        }

        val normalizedLang = when (lang.lowercase().trim()) {
            "en" -> "en"
            else -> "uk"
        }

        // Ключ кэша с точностью 5 знаков после запятой (~1.1 метра)
        val cacheKey = String.format(Locale.US, "geo:rev:v1:%s:%.5f:%.5f", normalizedLang, lat, lng)

        try {
            val cached = redisTemplate.opsForValue().get(cacheKey)?.toString()
            if (!cached.isNullOrBlank()) {
                val parts = cached.split("|||", limit = 3)
                if (parts.isNotEmpty() && parts[0].isNotBlank()) {
                    return ReverseGeocodeResponseDto(
                        address = parts[0],
                        rawDisplayName = parts.getOrNull(1)?.takeIf { it.isNotBlank() },
                        source = parts.getOrNull(2) ?: "redis_cache"
                    )
                }
            }
        } catch (e: Exception) {
            logger.warn(">>> [GeocodingService] Ошибка чтения кэша Redis: ${e.message}")
        }

        // 1. Запрос к OpenStreetMap Nominatim по точным координатам без округления
        val nominatimResult = fetchFromNominatim(lat, lng, normalizedLang)
        if (nominatimResult != null && nominatimResult.address.isNotBlank()) {
            try {
                val cacheValue = "${nominatimResult.address}|||${nominatimResult.rawDisplayName ?: ""}|||nominatim"
                redisTemplate.opsForValue().set(cacheKey, cacheValue, 24, TimeUnit.HOURS)
            } catch (e: Exception) {
                logger.warn(">>> [GeocodingService] Ошибка записи в Redis: ${e.message}")
            }
            return nominatimResult
        }

        // 2. Безотказный фоллбэк по полигонам секторов в памяти L1
        val sectorName = try {
            sectorService.findSectorByCoordinates(lat, lng)?.name
        } catch (e: Exception) {
            null
        }

        val fallbackText = if (!sectorName.isNullOrBlank()) {
            sectorName
        } else if (normalizedLang == "en") {
            "Point on map"
        } else {
            "Точка на карті"
        }

        return ReverseGeocodeResponseDto(
            address = fallbackText,
            rawDisplayName = null,
            source = if (sectorName != null) "sector_fallback" else "fallback"
        )
    }

    private fun fetchFromNominatim(lat: Double, lng: Double, lang: String): ReverseGeocodeResponseDto? {
        return try {
            val urlStr = "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=$lat&lon=$lng&zoom=18&addressdetails=1&accept-language=$lang"
            val connection = URL(urlStr).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.setRequestProperty("User-Agent", "UnitTaxiServer/1.0 (+https://unitua.com)")
            connection.setRequestProperty("Accept", "application/json")

            if (connection.responseCode == 200) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JsonParser.parseString(responseText).asJsonObject
                val displayName = json.get("display_name")?.takeIf { !it.isJsonNull }?.asString
                val addressObj = json.getAsJsonObject("address")

                val formatted = buildCleanAddress(addressObj, displayName)
                if (formatted.isNotBlank()) {
                    ReverseGeocodeResponseDto(
                        address = formatted,
                        rawDisplayName = displayName,
                        source = "nominatim"
                    )
                } else null
            } else {
                logger.warn(">>> [GeocodingService] Nominatim вернул HTTP ${connection.responseCode}")
                null
            }
        } catch (e: Exception) {
            logger.warn(">>> [GeocodingService] Ошибка запроса к Nominatim: ${e.message}")
            null
        }
    }

    private fun buildCleanAddress(addressObj: JsonObject?, displayName: String?): String {
        if (addressObj != null) {
            val road = getFirstString(
                addressObj,
                "road", "pedestrian", "footway", "street", "square", "residential", "Suburb", "suburb", "neighbourhood"
            )
            val houseNumber = getFirstString(addressObj, "house_number", "building")
            val city = getFirstString(addressObj, "city", "town", "village", "municipality", "hamlet")
            val poi = getFirstString(addressObj, "amenity", "shop", "office", "tourism", "building")

            val streetPart = when {
                !road.isNullOrBlank() && !houseNumber.isNullOrBlank() -> "${shortenStreetType(road)}, $houseNumber"
                !road.isNullOrBlank() -> shortenStreetType(road)
                !poi.isNullOrBlank() -> poi
                else -> ""
            }

            if (streetPart.isNotBlank()) {
                return if (!city.isNullOrBlank() && !streetPart.equals(city, ignoreCase = true)) {
                    "$streetPart ($city)"
                } else {
                    streetPart
                }
            }
        }

        if (!displayName.isNullOrBlank()) {
            val parts = displayName.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isNotEmpty()) {
                return parts.take(2).joinToString(", ")
            }
        }

        return ""
    }

    private fun getFirstString(obj: JsonObject, vararg keys: String): String? {
        for (key in keys) {
            if (obj.has(key) && !obj.get(key).isJsonNull) {
                val v = obj.get(key).asString.trim()
                if (v.isNotEmpty()) return v
            }
        }
        return null
    }

    private fun shortenStreetType(raw: String): String {
        var result = raw.trim()
        val replacements = mapOf(
            "вулиця" to "вул.",
            "улица" to "вул.",
            "проспект" to "пр-т",
            "провулок" to "пер.",
            "переулок" to "пер.",
            "набережна" to "наб.",
            "набережная" to "наб.",
            "бульвар" to "б-р",
            "шосе" to "ш.",
            "шоссе" to "ш.",
            "площа" to "пл.",
            "площадь" to "пл.",
            "майдан" to "м-н"
        )
        for ((full, short) in replacements) {
            result = result.replace(Regex("\\b$full\\b", RegexOption.IGNORE_CASE), short)
        }
        return result
    }
}