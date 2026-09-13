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
    private val redisTemplate: org.springframework.data.redis.core.RedisTemplate<String, Any>,
    private val taxiOrderTrackRepository: com.taxiapp.server.repository.TaxiOrderTrackRepository // 👈 ДОБАВЛЕНО
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

    // 🔥 НОВЫЙ ЭНДПОИНТ: Выборка истории перемещений из Postgres
    @GetMapping("/{id}/track")
    fun getOrderTrackFromDb(@PathVariable id: Long): ResponseEntity<List<Map<String, Any>>> {
        val tracks = taxiOrderTrackRepository.findByOrderIdOrderByTimestampAsc(id)
        val result = tracks.map {
            mapOf(
                "lat" to it.latitude,
                "lng" to it.longitude,
                "timestamp" to it.timestamp.toString()
            )
        }
        return ResponseEntity.ok(result)
    }

   @GetMapping("/{id}/track-history")
    fun getOrderTrackHistory(@PathVariable id: Long): ResponseEntity<List<Map<String, Any>>> {
        val order = taxiOrderRepository.findById(id).orElseThrow { RuntimeException("Замовлення не знайдено") }

        // Если водитель вообще не был назначен — реального трека передвижения водителя быть не может
        if (order.driver == null && !order.isEvosDriverAssigned) {
            return ResponseEntity.ok(emptyList())
        }

        // ШАГ 1: Достаем реальный трек водителя из постоянной таблицы Postgres
        val dbTracks = taxiOrderTrackRepository.findByOrderIdOrderByTimestampAsc(id)
        if (dbTracks.isNotEmpty()) {
            val history = dbTracks.map {
                mapOf(
                    "lat" to it.latitude,
                    "lng" to it.longitude,
                    "timestamp" to it.timestamp.toString()
                )
            }
            return ResponseEntity.ok(history)
        }

        // ШАГ 2: Если база пуста (например, заказ только завершился), читаем буфер из Redis
        val trackKey = "orders:track-history:${order.uuid}"
        val rawPoints = redisTemplate.opsForList().range(trackKey, 0, -1) ?: emptyList<Any>()
        val redisHistory = rawPoints.mapNotNull { raw ->
            val parts = raw.toString().split(",")
            if (parts.size >= 3) {
                mapOf(
                    "lat" to parts[0].toDouble(),
                    "lng" to parts[1].toDouble(),
                    "timestamp" to parts[2]
                )
            } else null
        }

        return ResponseEntity.ok(redisHistory)
    }
}