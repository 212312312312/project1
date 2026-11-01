package com.taxiapp.server.service

import com.taxiapp.server.dto.driver.DriverLocationDto
import com.taxiapp.server.dto.driver.UpdateLocationRequest
import com.taxiapp.server.dto.order.TrackingLocationDto
import com.taxiapp.server.model.enums.DriverSearchMode
import com.taxiapp.server.model.order.TaxiOrderTrack
import com.taxiapp.server.model.user.Driver
import com.taxiapp.server.repository.DriverRepository
import com.taxiapp.server.repository.TaxiOrderRepository
import com.taxiapp.server.repository.TaxiOrderTrackRepository
import org.slf4j.LoggerFactory
import org.springframework.data.geo.Circle
import org.springframework.data.geo.Distance
import org.springframework.data.geo.Metrics
import org.springframework.data.geo.Point
import org.springframework.data.redis.connection.RedisGeoCommands
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Service
class DriverLocationService(
    private val driverRepository: DriverRepository,
    private val orderRepository: TaxiOrderRepository,
    private val messagingTemplate: SimpMessagingTemplate,
    private val redisTemplate: RedisTemplate<String, Any>,
    private val taxiOrderTrackRepository: TaxiOrderTrackRepository,
    private val evoSService: EvoSService
) {
    private val logger = LoggerFactory.getLogger(DriverLocationService::class.java)

    private val GEO_KEY = "drivers:geo"
    private val META_KEY = "drivers:meta"
    private val UUID_MAP_KEY = "drivers:uuid-to-id"
    private val ACTIVE_DRIVERS_ORDER_KEY = "orders:active_drivers"

    // Локальный кэш последней записанной в БД точки трека (driverId -> Triple(lat, lng, timestampMs))
    // Защищает PostgreSQL от записи тысяч дубликатов координат стоящей на светофоре машины
    private val lastSavedTrackMap = ConcurrentHashMap<Long, Triple<Double, Double, Long>>()

    // Локальный потокобезопасный кэш машин EvoS по сетке координат (gridKey -> Pair(timestampMs, drivers))
    // Устраняет проблему двойной JSON-сериализации Jackson2JsonRedisSerializer в RedisTemplate<String, Any>
    private val evosDriversCache = ConcurrentHashMap<String, Pair<Long, List<DriverLocationDto>>>()

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
        val gridLat = String.format(Locale.US, "%.2f", lat)
        val gridLng = String.format(Locale.US, "%.2f", lng)
        val cacheKey = "$gridLat:$gridLng"
        val now = System.currentTimeMillis()

        // 1. Проверяем актуальный кэш (TTL 10 секунд)
        evosDriversCache[cacheKey]?.let { (cachedAt, cachedDrivers) ->
            if (now - cachedAt < 10_000L) {
                return cachedDrivers
            }
        }

        // Периодическая очистка устаревших ячеек сетки
        if (evosDriversCache.size > 500) {
            evosDriversCache.entries.removeIf { now - it.value.first > 30_000L }
        }

        // 2. Запрашиваем у EvoS ближайшие машины в радиусе 3 км (чтобы получать самые близкие к пину)
        val evosItems = evoSService.getNearbyDrivers(lat, lng, radiusKm = 3.0)

        val drivers = evosItems.mapIndexedNotNull { index, item ->
            val pos = item.drivercarPosition ?: return@mapIndexedNotNull null

            // В проде EvoS state для свободных машин может приходить как "Free", "free" или null
            val rawState = item.state?.trim()
            val isFree = rawState.isNullOrEmpty() || rawState.equals("Free", ignoreCase = true)

            // В проде EvoS status координат у партнерских авто (СОЗ) может быть "gpsOk" или null
            val rawPosStatus = pos.status?.trim()
            val isGpsOk = rawPosStatus.isNullOrEmpty() || rawPosStatus.equals("gpsOk", ignoreCase = true)

            if (!isFree || !isGpsOk) return@mapIndexedNotNull null

            val carLat = pos.lat ?: return@mapIndexedNotNull null
            val carLng = pos.lng ?: return@mapIndexedNotNull null
            if (carLat == 0.0 && carLng == 0.0) return@mapIndexedNotNull null

            // Стабильный отрицательный ID (или высокий диапазон) для партнерских авто, чтобы не пересекаться с ID своих водителей
            val coordRoundedLat = Math.round(carLat * 1000.0)
            val coordRoundedLng = Math.round(carLng * 1000.0)
            val pseudoId = 1_000_000_000L + Math.abs((coordRoundedLat * 31L + coordRoundedLng + index).hashCode().toLong() % 900_000_000L)
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

        evosDriversCache[cacheKey] = Pair(now, drivers)
        return drivers
    }

    /**
     * Быстрое обновление локации из DriverAppController без лишних SQL-запросов к таблице drivers и cars.
     * НЕ помечено @Transactional, чтобы GPS-пинги не занимали соединения из пула HikariCP!
     */
    fun updateLocation(driver: Driver, request: UpdateLocationRequest) {
        val driverId = driver.id ?: return
        val driverIdStr = driverId.toString()
        val existingMeta = redisTemplate.opsForHash<String, Any>().get(META_KEY, driverIdStr) as? Map<*, *>

        val fullName = driver.fullName ?: (existingMeta?.get("fullName") as? String) ?: "Водій"
        // Берем модель и цвет авто из Redis-меты (куда они пишутся при выходе на линию), чтобы не будить Lazy-запрос к таблице cars
        val carModel = (existingMeta?.get("carModel") as? String) ?: try { driver.car?.model } catch (_: Exception) { null } ?: "Не вказано"
        val carColor = (existingMeta?.get("carColor") as? String) ?: try { driver.car?.color } catch (_: Exception) { null } ?: ""
        val searchMode = driver.searchMode.name
        val isOnline = driver.isOnline

        processLocationUpdate(
            driverId = driverId,
            fullName = fullName,
            carModel = carModel,
            carColor = carColor,
            searchMode = searchMode,
            isOnline = isOnline,
            request = request
        )
    }

    /**
     * Обновление локации по UUID (сохранено для обратной совместимости).
     * Берет метаданные из Redis и обращается к PostgreSQL только один раз при холодном старте.
     */
    fun updateLocation(driverUuid: String, request: UpdateLocationRequest) {
        var driverIdStr = redisTemplate.opsForHash<String, String>().get(UUID_MAP_KEY, driverUuid)
        val driverId: Long
        var fullName = "Водій"
        var carModel = "Не вказано"
        var carColor = ""
        var searchMode = DriverSearchMode.MANUAL.name
        var isOnline = true

        if (driverIdStr != null) {
            driverId = driverIdStr.toLong()
            val existingMeta = redisTemplate.opsForHash<String, Any>().get(META_KEY, driverIdStr) as? Map<*, *>
            if (existingMeta != null) {
                fullName = existingMeta["fullName"] as? String ?: "Водій"
                carModel = existingMeta["carModel"] as? String ?: "Не вказано"
                carColor = existingMeta["carColor"] as? String ?: ""
                searchMode = existingMeta["status"] as? String ?: DriverSearchMode.MANUAL.name
                isOnline = (existingMeta["isOnline"] as? String)?.toBoolean() ?: true
            } else {
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
        } else {
            val driver = driverRepository.findByUuid(driverUuid).orElseThrow { RuntimeException("Driver not found") }
            driverId = driver.id!!
            driverIdStr = driverId.toString()
            fullName = driver.fullName ?: "Водій"
            carModel = driver.car?.model ?: "Не вказано"
            carColor = driver.car?.color ?: ""
            searchMode = driver.searchMode.name
            isOnline = driver.isOnline

            redisTemplate.opsForHash<String, String>().put(UUID_MAP_KEY, driverUuid, driverIdStr)
        }

        processLocationUpdate(
            driverId = driverId,
            fullName = fullName,
            carModel = carModel,
            carColor = carColor,
            searchMode = searchMode,
            isOnline = isOnline,
            request = request
        )
    }

    private fun processLocationUpdate(
        driverId: Long,
        fullName: String,
        carModel: String,
        carColor: String,
        searchMode: String,
        isOnline: Boolean,
        request: UpdateLocationRequest
    ) {
        val driverIdStr = driverId.toString()
        val newBearing = request.bearing ?: 0f
        val nowMs = System.currentTimeMillis()

        if (isOnline) {
            redisTemplate.opsForGeo().add(GEO_KEY, Point(request.lng, request.lat), driverIdStr)
        } else {
            redisTemplate.opsForGeo().remove(GEO_KEY, driverIdStr)
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
            "updatedAt" to nowMs.toString()
        )
        redisTemplate.opsForHash<String, Any>().put(META_KEY, driverIdStr, updatedMeta)

        // HIGH-LOAD ОПТИМИЗАЦИЯ: Проверяем наличие активного заказа сначала в Redis за O(1)!
        // Если водитель свободен (90%+ времени), мы НЕ делаем ни одного SQL-запроса к PostgreSQL!
        val activeOrderUuidObj = redisTemplate.opsForHash<String, Any>().get(ACTIVE_DRIVERS_ORDER_KEY, driverIdStr)
        val activeOrderUuid = activeOrderUuidObj?.toString()

        if (!activeOrderUuid.isNullOrBlank()) {
            val trackingDto = TrackingLocationDto(
                lat = request.lat,
                lng = request.lng,
                bearing = newBearing
            )
            messagingTemplate.convertAndSend("/topic/order/$activeOrderUuid/tracking", trackingDto)

            val trackKey = "orders:track-history:$activeOrderUuid"
            val timestamp = Instant.now().toString()
            redisTemplate.opsForList().rightPush(trackKey, "${request.lat},${request.lng},$timestamp")
            redisTemplate.expire(trackKey, 7, TimeUnit.DAYS)

            // Записываем точку в PostgreSQL только если водитель сдвинулся >= 10 метров или прошло >= 15 секунд
            val prevTrack = lastSavedTrackMap[driverId]
            val shouldSaveToDb = if (prevTrack == null) {
                true
            } else {
                val distMoved = calculateDistanceMeters(prevTrack.first, prevTrack.second, request.lat, request.lng)
                val timeElapsed = nowMs - prevTrack.third
                distMoved >= 10.0 || timeElapsed >= 15_000L
            }

            if (shouldSaveToDb) {
                try {
                    orderRepository.findActiveOrderByDriverId(driverId).ifPresent { order ->
                        val dbTrack = TaxiOrderTrack(
                            orderId = order.id!!,
                            latitude = request.lat,
                            longitude = request.lng
                        )
                        taxiOrderTrackRepository.save(dbTrack)
                        lastSavedTrackMap[driverId] = Triple(request.lat, request.lng, nowMs)
                    }
                } catch (e: Exception) {
                    logger.warn(">>> ОШИБКА СОХРАНЕНИЯ ТРЕКА В POSTGRES: {}", e.message)
                }
            }
        } else {
            lastSavedTrackMap.remove(driverId)
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
        lastSavedTrackMap.remove(driverId)
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
            .includeCoordinates().sortAscending().limit(10)

        // Ищем своих водителей в радиусе 2 км вокруг пина (вместо 10 км, которые блокировали EvoS по всему городу)
        val circle = Circle(Point(lng, lat), Distance(2.0, Metrics.KILOMETERS))
        val results = try {
            redisTemplate.opsForGeo().radius(GEO_KEY, circle, args)
        } catch (e: Exception) {
            logger.warn(">>> [DriverLocationService] Ошибка чтения GEO_KEY из Redis: ${e.message}")
            null
        }

        val now = System.currentTimeMillis()

        val localDrivers = results?.content?.mapNotNull { result ->
            val driverIdStr = result.content.name.toString()
            val point = result.content.point
            val meta = redisTemplate.opsForHash<String, Any>().get(META_KEY, driverIdStr) as? Map<*, *>

            if (meta == null) {
                // Очищаем "осиротевшую" запись из GEO_KEY, если метаданных больше нет
                redisTemplate.opsForGeo().remove(GEO_KEY, driverIdStr)
                return@mapNotNull null
            }

            val isOnline = (meta["isOnline"] as? String)?.toBoolean() ?: false
            val lastUpdate = (meta["updatedAt"] as? String)?.toLongOrNull() ?: 0L
            val isStale = lastUpdate > 0L && (now - lastUpdate > 60_000L)

            if (!isOnline || isStale) {
                // Удаляем неактивного/зависшего водителя из гео-индекса, чтобы он не блокировал выдачу
                redisTemplate.opsForGeo().remove(GEO_KEY, driverIdStr)
                if (isStale) {
                    redisTemplate.opsForHash<String, Any>().delete(META_KEY, driverIdStr)
                }
                return@mapNotNull null
            }

            // Не показываем как "свободных вокруг пина" тех водителей, которые сейчас выполняют активный заказ
            val hasActiveOrder = redisTemplate.opsForHash<String, Any>().hasKey(ACTIVE_DRIVERS_ORDER_KEY, driverIdStr)
            if (hasActiveOrder) {
                return@mapNotNull null
            }

            DriverLocationDto(
                driverId = driverIdStr.toLong(),
                fullName = meta["fullName"] as? String ?: "Водій",
                lat = point.y,
                lng = point.x,
                bearing = (meta["bearing"] as? String)?.toFloatOrNull() ?: 0f,
                status = meta["status"] as? String ?: "MANUAL",
                isOnline = true,
                carModel = meta["carModel"] as? String ?: "Не вказано",
                carColor = meta["carColor"] as? String ?: ""
            )
        } ?: emptyList()

        // Всегда добираем свободные машины из EvoS, если своих свободных машин в радиусе 2 км меньше 5
        val evosDrivers = if (localDrivers.size < 5) {
            getCachedEvoSDrivers(lat, lng)
                .filter { calculateDistanceMeters(lat, lng, it.lat, it.lng) <= 2000.0 }
        } else {
            emptyList()
        }

        return (localDrivers + evosDrivers)
            .sortedBy { calculateDistanceMeters(lat, lng, it.lat, it.lng) }
            .take(5)
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
            bearing = bearingStr?.toFloatOrNull()
        )
    }
}