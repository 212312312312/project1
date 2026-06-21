package com.taxiapp.server.config

import com.taxiapp.server.model.enums.DriverSearchMode
import com.taxiapp.server.repository.DriverRepository
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.socket.messaging.SessionDisconnectEvent

@Component
class WebSocketEventListener(
    private val driverRepository: DriverRepository
) {
    private val logger = LoggerFactory.getLogger(WebSocketEventListener::class.java)

    @EventListener
    @Transactional
    fun handleWebSocketDisconnectListener(event: SessionDisconnectEvent) {
        // Извлекаем авторизованного пользователя из сессии разорванного сокета
        val principal = event.user ?: return
        val username = principal.name

        // Ищем водителя по логину или телефону
        val driver = driverRepository.findByUserLogin(username)
            ?: driverRepository.findByUserPhone(username)

        driver?.let {
            // Жестко переводим в офлайн, так как приложение закрыто
            it.isOnline = false
            
            // --- СТАЛО (СБРОС В MANUAL ПРИ ДРОПЕ СОКЕТА): ---
            if (it.searchMode == DriverSearchMode.CHAIN || it.searchMode == DriverSearchMode.HOME) {
                it.searchMode = DriverSearchMode.MANUAL
            }
            
            driverRepository.save(it)
            
            logger.info("[WS_DISCONNECT] Водій ID=${it.id} ($username) закрыл приложение. Статус офлайн применен, режим переведен в MANUAL.")
        }
    }
}