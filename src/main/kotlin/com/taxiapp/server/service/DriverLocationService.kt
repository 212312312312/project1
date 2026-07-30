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

@Service // 👈 ВОТ ЭТА АННОТАЦИЯ ОБЯЗАТЕЛЬНО ДОЛЖНА БЫТЬ ТУТ
class DriverLocationService(
    private val driverRepository: DriverRepository,
    private val orderRepository: TaxiOrderRepository,
    private val messagingTemplate: SimpMessagingTemplate,
    private val redisTemplate: RedisTemplate<String, Any>,
    private val taxiOrderTrackRepository: com.taxiapp.server.repository.TaxiOrderTrackRepository
) {
    // Ключи для Redis
    private val GEO_KEY = "drivers:geo"
    private val META_KEY = "drivers:meta"
    private val UUID_MAP_KEY = "drivers:uuid-to-id"

    @Transactional
    fun updateLocation(driverUuid: String, request: UpdateLocationRequest) {
        // 1. Пытаемся быстро взять ID водителя из кэша Redis, чтобы не делать SELECT по UUID в базу
        val driverIdStr = redisTemplate.opsForHash<String, String>().get(UUID_MAP_KEY, driverUuid)
        val driverId: Long
        var fullName = "Водій"
        var carModel = "Не вказано"
        var carColor = ""
        var searchMode = DriverSearchMode.MANUAL.name
        var isOnline = true

        if (driverIdStr == null) {
            // Если в кэше нет — ОДИН раз запрашиваем БД и кэшируем маппинг в Redis
            val driver = driverRepository.findByUuid(driverUuid).orElseThrow { RuntimeException("Driver not found") }
            driverId = driver.id!!
            fullName = driver.fullName ?: "Водій"
            carModel = driver.car?.model ?: "Не вказано"
            carColor = driver.car?.color ?: ""
            searchMode = driver.searchMode.name
            isOnline = driver.isOnline
            
            redisTemplate.opsForHash<String, String>().put(UUID_MAP_KEY, driverUuid, driverId.toString())
            
            val meta = mapOf(
                "fullName" to fullName,
                "carModel" to carModel,
                "carColor" to carColor,
                "status" to searchMode,
                "isOnline" to isOnline.toString()
            )
            redisTemplate.opsForHash<String, Any>().put(META_KEY, driverId.toString(), meta)
        } else {
            driverId = driverIdStr.toLong()
            // Извлекаем статичную мету из Redis
            val meta = redisTemplate.opsForHash<String, Any>().get(META_KEY, driverId.toString()) as? Map<*, *>
            if (meta != null) {
                fullName = meta["fullName"] as? String ?: "Водій"
                carModel = meta["carModel"] as? String ?: "Не вказано"
                carColor = meta["carColor"] as? String ?: ""
                searchMode = meta["status"] as? String ?: DriverSearchMode.MANUAL.name
                isOnline = (meta["isOnline"] as? String)?.toBoolean() ?: true
            }
        }

        val newBearing = request.bearing ?: 0f

        // 2. Вместо сохранения в БД, пишем гео-точку в оперативку Redis GEO
        redisTemplate.opsForGeo().add(GEO_KEY, Point(request.lng, request.lat), driverId.toString())

        // Обновляем динамические данные в хэш-карте Redis
        val updatedMeta = mapOf(
            "fullName" to fullName,
            "carModel" to carModel,
            "carColor" to carColor,
            "status" to searchMode,
            "isOnline" to isOnline.toString(),
            "lat" to request.lat.toString(),
            "lng" to request.lng.toString(),
            "bearing" to newBearing.toString()
        )
        redisTemplate.opsForHash<String, Any>().put(META_KEY, driverId.toString(), updatedMeta)

        // === ЗАМЕНИ ТОЛЬКО ЭТОТ БЛОК (Пункт 3) ===
        // 3. Ретрансляция (Tracking) для активного заказа клиента + Логирование реального трека
        val orderUuidStr = redisTemplate.opsForHash<String, Any>().get("orders:active_drivers", driverId.toString())?.toString()
        
        if (!orderUuidStr.isNullOrEmpty()) {
            val trackingDto = TrackingLocationDto(
                lat = request.lat,
                lng = request.lng,
                bearing = newBearing
            )
            // Пушим координаты напрямую в сокет-канал конкретной поездки пассажира
            messagingTemplate.convertAndSend("/topic/order/$orderUuidStr/tracking", trackingDto)

            // 🔥 РЕАЛЬНЫЙ ТРЕКИНГ: Записываем физическую координату машины в список Redis
            val trackKey = "orders:track-history:$orderUuidStr"
            val timestamp = java.time.Instant.now().toString() // Точное серверное ISO время точки
            val trackPoint = "${request.lat},${request.lng},$timestamp"
            
            redisTemplate.opsForList().rightPush(trackKey, trackPoint)
            // Устанавливаем TTL на 7 дней, чтобы треки старых поездок автоматически стирались и не забивали оперативку
            redisTemplate.expire(trackKey, 7, java.util.concurrent.TimeUnit.DAYS)

            // 🔥 НАШ ДОВЕСОК ДЛЯ ПЕРСИСТЕНТНОГО ТРЕКА В POSTGRES
            try {
                orderRepository.findActiveOrderByDriverId(driverId).ifPresent { order ->
                    val statusStr = order.status.name
                    // Добавили ACCEPTED, чтобы трек писался сразу, как только водитель принял заказ и едет к клиенту
                    if (statusStr == "ACCEPTED" || statusStr == "IN_PROGRESS" || statusStr == "DRIVER_ARRIVED" || statusStr == "ARRIVED") { // 👈 ИЗМЕНЕНО
                        val dbTrack = com.taxiapp.server.model.order.TaxiOrderTrack(
                            orderId = order.id!!,
                            latitude = request.lat,
                            longitude = request.lng
                        )
                        taxiOrderTrackRepository.save(dbTrack)
                    }
                }
            } catch (e: Exception) {
                println(">>> ОШИБКА СОХРАНЕНИЯ ТРЕКА В POSTGRES: ${e.message}")
            }
        }

        // 4. Трансляция координат на общую веб-карту для диспетчера
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

        // Очищаем реалтайм кэш из Redis
        redisTemplate.opsForGeo().remove(GEO_KEY, driverId.toString())
        redisTemplate.opsForHash<String, Any>().delete(META_KEY, driverId.toString())

        messagingTemplate.convertAndSend("/topic/admin/drivers/locations", DriverLocationDto(driver))
    }

    fun getTop5NearestDrivers(lat: Double, lng: Double): List<DriverLocationDto> {
        val args = RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
            .includeCoordinates().sortAscending().limit(5)
            
        // Ищем в радиусе 10 километров через оперативную память Redis
        val circle = Circle(Point(lng, lat), Distance(10.0, Metrics.KILOMETERS))
        val results = redisTemplate.opsForGeo().radius(GEO_KEY, circle, args) ?: return emptyList()
        
        return results.content.map { result ->
            val driverIdStr = result.content.name
            val point = result.content.point
            // Явно приводим driverIdStr к String через .toString()
            val meta = redisTemplate.opsForHash<String, Any>().get(META_KEY, driverIdStr.toString()) as? Map<*, *>
            
            DriverLocationDto(
                driverId = driverIdStr.toString().toLong(), // <-- ФИКС: Сначала в String, потом в Long
                fullName = meta?.get("fullName") as? String ?: "Водій",
                lat = point.y,
                lng = point.x,
                bearing = (meta?.get("bearing") as? String)?.toFloat() ?: 0f,
                status = meta?.get("status") as? String ?: "MANUAL",
                isOnline = (meta?.get("isOnline") as? String)?.toBoolean() ?: true,
                carModel = meta?.get("carModel") as? String ?: "Не вказано",
                carColor = meta?.get("carColor") as? String ?: ""
            )
        }
    }

    fun getOnlineDriversForMap(): List<DriverLocationDto> {
        // center локация Киева для UNIT такси
        val center = Point(30.5234, 50.4501) 
        // Ищем всех активных водителей в радиусе 100 км от центра города через Redis GEO
        val circle = Circle(center, Distance(100.0, Metrics.KILOMETERS))
        val args = RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs().includeCoordinates()
        val geoResults = redisTemplate.opsForGeo().radius(GEO_KEY, circle, args) ?: return emptyList()

        val driverIds = geoResults.content.map { it.content.name.toString() }
        if (driverIds.isEmpty()) return emptyList()

        // 🚀 ВЫСОКОНАГРУЖЕННЫЙ ХИТ: вытягиваем мету пачкой через HMGET за O(N) вместо блокирующего HGETALL
        val metaList = redisTemplate.opsForHash<String, Any>().multiGet(META_KEY, driverIds)

        return geoResults.content.mapIndexedNotNull { index, result ->
            val driverIdStr = result.content.name.toString()
            val point = result.content.point
            val meta = metaList.getOrNull(index) as? Map<*, *> ?: return@mapIndexedNotNull null

            DriverLocationDto(
                driverId = driverIdStr.toLong(),
                fullName = meta["fullName"] as? String ?: "Водій",
                lat = point.y,
                lng = point.x,
                bearing = (meta["bearing"] as? String)?.toFloat() ?: 0f,
                status = meta["status"] as? String ?: "MANUAL",
                isOnline = (meta["isOnline"] as? String)?.toBoolean() ?: false,
                carModel = meta["carModel"] as? String ?: "Не вказано",
                carColor = meta["carColor"] as? String ?: ""
            )
        }
    }


    // Добавь этот метод в самый конец класса DriverLocationService (перед последней закрывающей фигурной скобкой):

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