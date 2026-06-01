package com.taxiapp.server.controller

import com.taxiapp.server.dto.driver.DriverLocationDto
import com.taxiapp.server.service.DriverLocationService
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Controller

// DTO для получения запроса от клиента
data class ClientLocationRequest(
    val clientId: String, // Уникальный ID клиента или сессии
    val lat: Double,
    val lng: Double
)

@Controller
class ClientLocationWsController(
    private val driverLocationService: DriverLocationService,
    private val messagingTemplate: SimpMessagingTemplate
) {

    // Слушаем сообщения по адресу /app/client/location
    @MessageMapping("/client/location")
    fun handleClientLocationUpdate(request: ClientLocationRequest) {
        // ЛОГ 1: Видим ли мы запрос от клиента?
        println("🚀 [WS] Пришли координаты от клиента ${request.clientId}: lat=${request.lat}, lng=${request.lng}")
        
        val nearbyDrivers = driverLocationService.getTop5NearestDrivers(request.lat, request.lng)
        
        // ЛОГ 2: Сколько машин нашел SQL запрос?
        println("🚕 [WS] Найдено машин рядом: ${nearbyDrivers.size}")
        if (nearbyDrivers.isNotEmpty()) {
            println("🚕 [WS] Первая машина в списке: ${nearbyDrivers[0].driverId} - ${nearbyDrivers[0].status}")
        }
        
        messagingTemplate.convertAndSend("/topic/nearby-drivers/${request.clientId}", nearbyDrivers)
    }
}