package com.taxiapp.server.config

import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.web.socket.messaging.SessionDisconnectEvent

@Component
class WebSocketEventListener {

    private val logger = LoggerFactory.getLogger(WebSocketEventListener::class.java)

    @EventListener
    fun handleWebSocketDisconnectListener(event: SessionDisconnectEvent) {
        val principal = event.user ?: return
        val username = principal.name

        // Сокет используется только для экрана Эфира и чатов. 
        // При выходе с экрана эфира в MainActivity сокет закрывается, 
        // но водитель остаётся онлайн на смене. Статус в БД и Redis НЕ сбрасываем.
        logger.info("[WS_DISCONNECT] Сокет-сесія закрита для користувача: $username. Онлайн-статус водія на зміні збережено.")
    }
}