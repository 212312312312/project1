package com.taxiapp.server.scheduler

import com.taxiapp.server.service.DriverLocationService
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class DriverLocationScheduler(
    private val driverLocationService: DriverLocationService,
    private val messagingTemplate: SimpMessagingTemplate
) {

    @Scheduled(fixedRate = 3000) // 🚀 Каждые 3 секунды транслируем координаты активных водителей на карту
    fun broadcastDriversLocationToAdmin() {
        try {
            val drivers = driverLocationService.getOnlineDriversForMap()
            if (drivers.isNotEmpty()) {
                // Отправляем массив координат диспетчерам, подписанным на этот топик
                messagingTemplate.convertAndSend("/topic/admin/drivers-location", drivers)
            }
        } catch (e: Exception) {
            println(">>> Ошибка трансляции координат в WebSocket диспетчеров: ${e.message}")
        }
    }
}