package com.taxiapp.server.config

import com.taxiapp.server.dto.driver.DriverLocationDto
import com.taxiapp.server.model.enums.DriverSearchMode
import com.taxiapp.server.repository.DriverRepository
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.socket.messaging.SessionDisconnectEvent

@Component
class WebSocketEventListener(
    private val driverRepository: DriverRepository,
    private val redisTemplate: org.springframework.data.redis.core.RedisTemplate<String, Any>,
    private val messagingTemplate: SimpMessagingTemplate // 👈 Внедрен messagingTemplate
) {
    private val logger = LoggerFactory.getLogger(WebSocketEventListener::class.java)

    @EventListener
    @Transactional
    fun handleWebSocketDisconnectListener(event: SessionDisconnectEvent) {
        val principal = event.user ?: return
        val username = principal.name

        val driver = driverRepository.findByUserLogin(username)
            ?: driverRepository.findByUserPhone(username)

        driver?.let {
            it.isOnline = false
            if (it.searchMode == DriverSearchMode.CHAIN || it.searchMode == DriverSearchMode.HOME) {
                it.searchMode = DriverSearchMode.MANUAL
            }
            driverRepository.save(it)

            // 🟢 Очищаем локацию и отправляем сигнал полного удаления с карты диспетчера
            val driverIdStr = it.id.toString()
            redisTemplate.opsForGeo().remove("drivers:geo", driverIdStr)
            redisTemplate.opsForHash<String, Any>().delete("drivers:meta", driverIdStr)

            // Конструируем DTO через конструктор (без попыток перезаписи val-полей)
            val offlineDto = DriverLocationDto(
                driverId = it.id!!,
                fullName = it.fullName ?: "Водій",
                lat = 0.0,
                lng = 0.0,
                bearing = 0f,
                status = it.searchMode.name,
                isOnline = false,
                carModel = it.car?.model ?: "Не вказано",
                carColor = it.car?.color ?: ""
            )
            messagingTemplate.convertAndSend("/topic/admin/drivers/locations", offlineDto)

            logger.info("[WS_DISCONNECT] Водій ID=${it.id} ($username) закрыл приложение. Очистка выполнена.")
        }
    }
}