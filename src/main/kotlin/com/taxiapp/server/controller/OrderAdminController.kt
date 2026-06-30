package com.taxiapp.server.controller

import com.taxiapp.server.dto.order.TaxiOrderDto
import com.taxiapp.server.service.OrderAdminService
import com.taxiapp.server.service.OrderService // <--- Не забудьте этот импорт!
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/admin/orders")
class OrderAdminController(
    private val orderAdminService: OrderAdminService,
    private val orderService: OrderService,
    private val taxiOrderRepository: com.taxiapp.server.repository.TaxiOrderRepository,
    private val redisTemplate: org.springframework.data.redis.core.RedisTemplate<String, Any> // <--- ДОБАВИЛИ ДЛЯ ЧТЕНИЯ РЕАЛЬНОГО ТРЕКА
) {

    // "Активные заказы" (Real-time update 10 сек)
    @GetMapping("/active")
    fun getActiveOrders(): ResponseEntity<List<TaxiOrderDto>> {
        // Теперь orderService существует и ошибки не будет
        val orders = orderService.getActiveOrdersForDispatcher()
        return ResponseEntity.ok(orders)
    }

    // "Архив заказов"
    @GetMapping("/archive")
    fun getArchivedOrders(): ResponseEntity<List<TaxiOrderDto>> {
        return ResponseEntity.ok(orderAdminService.getArchivedOrders())
    }

    // Поиск по Архиву (по номеру клиента или водителя)
    @GetMapping("/archive/search")
    fun searchArchive(@RequestParam phone: String): ResponseEntity<List<TaxiOrderDto>> {
        return ResponseEntity.ok(orderAdminService.searchArchive(phone))
    }
    
    // Отменить заказ
    @PostMapping("/{id}/cancel")
    fun cancelOrder(@PathVariable id: Long): ResponseEntity<TaxiOrderDto> {
        return ResponseEntity.ok(orderAdminService.cancelOrder(id))
    }

    // Назначить водителя на заказ
    @PostMapping("/{id}/assign")
    fun assignDriver(
        @PathVariable id: Long,
        @RequestParam driverId: Long 
    ): ResponseEntity<TaxiOrderDto> {
        return ResponseEntity.ok(orderAdminService.assignDriverToOrder(id, driverId))
    }

   @GetMapping("/{id}/track-history")
    fun getOrderTrackHistory(@PathVariable id: Long): ResponseEntity<List<Map<String, Any>>> {
        val order = taxiOrderRepository.findById(id).orElseThrow { RuntimeException("Заказ не найден") }
        val trackKey = "orders:track-history:${order.uuid}"
        
        // 🚀 ШАГ 1: Проверяем наличие реальных физических координат в Redis
        val redisListSize = redisTemplate.opsForList().size(trackKey) ?: 0L
        if (redisListSize > 0) {
            val rawPoints = redisTemplate.opsForList().range(trackKey, 0, -1) ?: emptyList<Any>()
            val realHistory = rawPoints.mapNotNull { raw ->
                val str = raw.toString()
                val parts = str.split(",")
                if (parts.size >= 3) {
                    mapOf(
                        "lat" to parts[0].toDouble(),
                        "lng" to parts[1].toDouble(),
                        "timestamp" to parts[2]
                    )
                } else null
            }
            if (realHistory.isNotEmpty()) {
                // Если водитель ехал и слал координаты — отдаем чистую правду
                return ResponseEntity.ok(realHistory)
            }
        }
        
        // 🛡️ ШАГ 2: Умный фолбек (если заказ старее 7 дней или у водителя пропал интернет/GPS)
        val start = order.startedAt ?: order.createdAt
        val end = order.completedAt ?: start.plusMinutes(15)
        val coords = ArrayList<Pair<Double, Double>>()
        val polyline = order.googleRoutePolyline
        
        if (!polyline.isNullOrBlank()) {
            try {
                var index = 0
                val len = polyline.length
                var lat = 0
                var lng = 0
                while (index < len) {
                    var b: Int
                    var shift = 0
                    var result = 0
                    do {
                        b = polyline[index++].code - 63
                        result = result or ((b and 0x1f) shl shift)
                        shift += 5
                    } while (b >= 0x20)
                    val dlat = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
                    lat += dlat
                    shift = 0
                    result = 0
                    do {
                        b = polyline[index++].code - 63
                        result = result or ((b and 0x1f) shl shift)
                        shift += 5
                    } while (b >= 0x20)
                    val dlng = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
                    lng += dlng
                    coords.add(Pair(lat.toDouble() / 1E5, lng.toDouble() / 1E5))
                }
            } catch (e: Exception) {
                coords.clear()
            }
        }
        
        if (coords.isEmpty()) {
            val oLat = order.originLat ?: 50.4501
            val oLng = order.originLng ?: 30.5234
            val dLat = order.destLat ?: oLat
            val dLng = order.destLng ?: oLng
            coords.add(Pair(oLat, oLng))
            coords.add(Pair(dLat, dLng))
        }
        
        val totalPoints = coords.size
        val durationSeconds = java.time.Duration.between(start, end).seconds
        val stepSeconds = if (totalPoints > 1) durationSeconds / (totalPoints - 1) else 0L
        
        val fallbackHistory = coords.mapIndexed { idx, pair ->
            val pointTime = start.plusSeconds(idx * stepSeconds)
            mapOf(
                "lat" to pair.first,
                "lng" to pair.second,
                "timestamp" to pointTime.toString()
            )
        }
        
        return ResponseEntity.ok(fallbackHistory)
    }
}