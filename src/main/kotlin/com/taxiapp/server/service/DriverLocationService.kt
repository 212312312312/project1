package com.taxiapp.server.service

import com.taxiapp.server.dto.driver.DriverLocationDto
import com.taxiapp.server.dto.driver.UpdateLocationRequest
import com.taxiapp.server.dto.order.TrackingLocationDto
import com.taxiapp.server.model.enums.DriverSearchMode
import com.taxiapp.server.repository.DriverRepository
import com.taxiapp.server.repository.TaxiOrderRepository
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.geo.Point
import org.springframework.data.geo.Circle
import org.springframework.data.geo.Distance
import org.springframework.data.geo.Metrics
import org.springframework.data.redis.connection.RedisGeoCommands

@Service
class DriverLocationService(
    private val driverRepository: DriverRepository,
    private val orderRepository: TaxiOrderRepository,
    private val messagingTemplate: SimpMessagingTemplate,
    private val redisTemplate: RedisTemplate<String, Any>,
    private val taxiOrderTrackRepository: com.taxiapp.server.repository.TaxiOrderTrackRepository,
    private val evoSService: EvoSService // 👈 ДОБАВЛЕНО
) {
    // Ключи для Redis
    private val GEO_KEY = "drivers:geo"
    private val META_KEY = "drivers:meta"
    private val UUID_MAP_KEY = "drivers:uuid-to-id"
    private val objectMapper = com.fasterxml.jackson.databind.ObjectMapper()
        .registerModule(com.fasterxml.jackson.module.kotlin.KotlinModule.Builder().build())
        .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)


    private fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }

    private fun getCachedEvoSDrivers(lat: Double, lng: Double): List<DriverLocationDto> {
        val gridLat = String.format(java.util.Locale.US, "%.2f", lat)
        val gridLng = String.format(java.util.Locale.US, "%.2f", lng)
        val cacheKey = "evos:drivers:cache:$gridLat:$gridLng"

        val cachedJson = try {
            redisTemplate.opsForValue().get(cacheKey) as? String
        } catch (e: Exception) {
            null
        }

        if (!cachedJson.isNullOrBlank()) {
            return try {
                val listType = objectMapper.typeFactory.constructCollectionType(List::class.java, DriverLocationDto::class.java)
                objectMapper.readValue<List<DriverLocationDto>>(cachedJson, listType)
            } catch (e: Exception) {
                println("⚠️ [Redis Deserialization Error]: ${e.message}")
                emptyList()
            }
        }

        // Запрашиваем радиус 6.0 км
        val evosItems = evoSService.getNearbyDrivers(lat, lng, radiusKm = 6.0)
        
        val drivers = evosItems.mapNotNull { item ->
            val pos = item.drivercarPosition ?: return@mapNotNull null
            val isFree = item.state?.equals("Free", ignoreCase = true) == true
            val isGpsOk = pos.status?.equals("gpsOk", ignoreCase = true) == true

            if (!isFree || !isGpsOk) {
                println("⚠️ [EvoS Driver Filtered] state=${item.state}, gpsStatus=${pos.status}")
                return@mapNotNull null
            }

            val carLat = pos.lat ?: return@mapNotNull null
            val carLng = pos.lng ?: return@mapNotNull null
            if (carLat == 0.0 && carLng == 0.0) return@mapNotNull null

            val pseudoId = Math.abs((carLat.toString() + carLng.toString()).hashCode().toLong())
            val carModel = item.car?.types?.firstOrNull() ?: "Стандарт"

            DriverLocationDto(
                driverId = pseudoId,
                fullName = "Партнер",
                lat = carLat,
                lng = carLng,
                bearing = pos.bearing ?: 0f,
                status = "FREE",
                isOnline = true,
                carModel = carModel,
                carColor = ""
            )
        }

        println("🚕 [EvoS Valid Drivers] Пройшло фільтрацію вільних авто: ${drivers.size} з ${evosItems.size}")

        try {
            val jsonToCache = objectMapper.writeValueAsString(drivers)
            redisTemplate.opsForValue().set(cacheKey, jsonToCache, 10, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) {}

        return drivers
    }

    @Transactional
    fun updateLocation(driverUuid: String, request: UpdateLocationRequest) {
        val driverIdStr = redisTemplate.opsForHash<String, String>().get(UUID_MAP_KEY, driverUuid)
        val driverId: Long
        var fullName = "Водій"
        var carModel = "Не вказано"
        var carColor = ""
        var searchMode = DriverSearchMode.MANUAL.name
        var isOnline = true

        if (driverIdStr == null) {
            val driver = driverRepository.findByUuid(driverUuid).orElseThrow { RuntimeException("Driver not found") }
            driverId = driver.id!!
            fullName = driver.fullName ?: "Водій"
            carModel = driver.car?.model ?: "Не вказано"
            carColor = driver.car?.color ?: ""
            searchMode = driver.searchMode.name
            isOnline = driver.isOnline
            
            redisTemplate.opsForHash<String, String>().put(UUID_MAP_KEY, driverUuid, driverId.toString())
        } else {
            driverId = driverIdStr.toLong()
            val driver = driverRepository.findById(driverId).orElse(null)
            if (driver != null) {
                fullName = driver.fullName ?: "Водій"
                carModel = driver.car?.model ?: "Не вказано"
                carColor = driver.car?.color ?: ""
                searchMode = driver.searchMode.name
                isOnline = driver.isOnline
            } else {
                isOnline = false
            }
        }

        val newBearing = request.bearing ?: 0f

        if (isOnline) {
            redisTemplate.opsForGeo().add(GEO_KEY, Point(request.lng, request.lat), driverId.toString())
        } else {
            redisTemplate.opsForGeo().remove(GEO_KEY, driverId.toString())
        }

        val updatedMeta = mapOf(
            "fullName" to fullName,
            "carModel" to carModel,
            "carColor" to carColor,
            "status" to searchMode,
            "isOnline" to isOnline.toString(),
            "lat" to request.lat.toString(),
            "lng" to request.lng.toString(),
            "bearing" to newBearing.toString(),
            "updatedAt" to System.currentTimeMillis().toString()
        )
        redisTemplate.opsForHash<String, Any>().put(META_KEY, driverId.toString(), updatedMeta)

        // 1. Проверяем активный заказ напрямую в БД (гарантированная запись трека)
        try {
            orderRepository.findActiveOrderByDriverId(driverId).ifPresent { order ->
                val dbTrack = com.taxiapp.server.model.order.TaxiOrderTrack(
                    orderId = order.id!!,
                    latitude = request.lat,
                    longitude = request.lng
                )
                taxiOrderTrackRepository.save(dbTrack)

                val orderUuid = order.uuid.toString()
                val trackingDto = TrackingLocationDto(
                    lat = request.lat,
                    lng = request.lng,
                    bearing = newBearing
                )
                messagingTemplate.convertAndSend("/topic/order/$orderUuid/tracking", trackingDto)

                val trackKey = "orders:track-history:$orderUuid"
                val timestamp = java.time.Instant.now().toString()
                redisTemplate.opsForList().rightPush(trackKey, "${request.lat},${request.lng},$timestamp")
                redisTemplate.expire(trackKey, 7, java.util.concurrent.TimeUnit.DAYS)
            }
        } catch (e: Exception) {
            println(">>> ОШИБКА СОХРАНЕНИЯ ТРЕКА В POSTGRES: ${e.message}")
        }

        val locationDto = DriverLocationDto(
            driverId = driverId,
            fullName = fullName,
            lat = request.lat,
            lng = request.lng,
            bearing = newBearing,
            status = searchMode,
            isOnline = isOnline,
            carModel = carModel,
            carColor = carColor
        )
        messagingTemplate.convertAndSend("/topic/admin/drivers/locations", locationDto)
    }

    @Transactional
    fun clearLocation(driverId: Long) {
        val driver = driverRepository.findById(driverId).orElse(null) ?: return
        driver.latitude = null
        driver.longitude = null
        if (driver.searchMode == DriverSearchMode.CHAIN || driver.searchMode == DriverSearchMode.HOME) {
            driver.searchMode = DriverSearchMode.MANUAL
        }
        driver.isOnline = false
        driverRepository.save(driver)

        redisTemplate.opsForGeo().remove(GEO_KEY, driverId.toString())
        redisTemplate.opsForHash<String, Any>().delete(META_KEY, driverId.toString())

        messagingTemplate.convertAndSend("/topic/admin/drivers/locations", DriverLocationDto(driver))
    }

    fun getTop5NearestDrivers(lat: Double, lng: Double): List<DriverLocationDto> {
        val args = RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
            .includeCoordinates().sortAscending().limit(5)
            
        val circle = Circle(Point(lng, lat), Distance(10.0, Metrics.KILOMETERS))
        val results = redisTemplate.opsForGeo().radius(GEO_KEY, circle, args)
        
        val localDrivers = results?.content?.map { result ->
            val driverIdStr = result.content.name
            val point = result.content.point
            val meta = redisTemplate.opsForHash<String, Any>().get(META_KEY, driverIdStr.toString()) as? Map<*, *>
            
            DriverLocationDto(
                driverId = driverIdStr.toString().toLong(),
                fullName = meta?.get("fullName") as? String ?: "Водій",
                lat = point.y,
                lng = point.x,
                bearing = (meta?.get("bearing") as? String)?.toFloat() ?: 0f,
                status = meta?.get("status") as? String ?: "MANUAL",
                isOnline = (meta?.get("isOnline") as? String)?.toBoolean() ?: true,
                carModel = meta?.get("carModel") as? String ?: "Не вказано",
                carColor = meta?.get("carColor") as? String ?: ""
            )
        } ?: emptyList()

        // Если в системе есть свои активные водители — отдаем их
        if (localDrivers.isNotEmpty()) {
            return localDrivers
        }

        // Если своих нет — берем машины из кэша EvoS в радиусе до 800м от пина клиента
        val evosDrivers = getCachedEvoSDrivers(lat, lng)
        return evosDrivers
            .map { driver -> Pair(driver, calculateDistanceMeters(lat, lng, driver.lat, driver.lng)) }
            .filter { it.second <= 2000.0 } // 👈 Расширили до 2 км
            .sortedBy { it.second }
            .take(5)
            .map { it.first }
    }

    fun getAllActiveDriversForMap(): List<DriverLocationDto> {
        val keys = redisTemplate.opsForHash<String, Any>().keys(META_KEY)
        if (keys.isEmpty()) return emptyList()

        val driverIds = keys.map { it.toString() }
        val metaList = redisTemplate.opsForHash<String, Any>().multiGet(META_KEY, driverIds)
        val now = System.currentTimeMillis()

        return driverIds.mapIndexedNotNull { index, driverIdStr ->
            val meta = metaList.getOrNull(index) as? Map<*, *> ?: return@mapIndexedNotNull null

            val latStr = meta["lat"] as? String ?: return@mapIndexedNotNull null
            val lngStr = meta["lng"] as? String ?: return@mapIndexedNotNull null
            val lat = latStr.toDoubleOrNull() ?: return@mapIndexedNotNull null
            val lng = lngStr.toDoubleOrNull() ?: return@mapIndexedNotNull null

            // 🛡️ Защита от "зависших" водителей: если от мобилки не было координат > 60 секунд, очищаем кэш
            val lastUpdateStr = meta["updatedAt"] as? String
            val lastUpdate = lastUpdateStr?.toLongOrNull() ?: 0L
            if (lastUpdate > 0 && (now - lastUpdate > 60_000)) {
                redisTemplate.opsForGeo().remove(GEO_KEY, driverIdStr)
                redisTemplate.opsForHash<String, Any>().delete(META_KEY, driverIdStr)
                return@mapIndexedNotNull null
            }

            val isOnline = (meta["isOnline"] as? String)?.toBoolean() ?: false

            DriverLocationDto(
                driverId = driverIdStr.toLong(),
                fullName = meta["fullName"] as? String ?: "Водій",
                lat = lat,
                lng = lng,
                bearing = (meta["bearing"] as? String)?.toFloatOrNull() ?: 0f,
                status = meta["status"] as? String ?: "MANUAL",
                isOnline = isOnline,
                carModel = meta["carModel"] as? String ?: "Не вказано",
                carColor = meta["carColor"] as? String ?: ""
            )
        }
    }

    fun getDriverLocation(driverId: Long): UpdateLocationRequest? {
        val meta = redisTemplate.opsForHash<String, Any>().get(META_KEY, driverId.toString()) as? Map<*, *> ?: return null
        val latStr = meta["lat"] as? String ?: return null
        val lngStr = meta["lng"] as? String ?: return null
        val bearingStr = meta["bearing"] as? String
        return UpdateLocationRequest(
            lat = latStr.toDouble(),
            lng = lngStr.toDouble(),
            bearing = bearingStr?.toFloat()
        )
    }
}