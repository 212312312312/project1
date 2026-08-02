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

    @Scheduled(fixedRate = 3000) // 🚀 Каждые 3 секунды транслируем координаты всех активных водителей приложения
    fun broadcastDriversLocationToAdmin() {
        try {
            val drivers = driverLocationService.getAllActiveDriversForMap()
            // 🛡️ Транслируем ВСЕГДА (даже если список пустой []), чтобы диспетчерская вовремя обнуляла счетчики и убирала маркеры
            messagingTemplate.convertAndSend("/topic/admin/drivers-location", drivers)
        } catch (e: Exception) {
            println(">>> Ошибка трансляции координат в WebSocket диспетчеров: ${e.message}")
        }
    }
}