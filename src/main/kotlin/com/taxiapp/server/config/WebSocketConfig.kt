package com.taxiapp.server.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer

@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig : WebSocketMessageBrokerConfigurer {

    // Читаем список доменов из application.properties (по умолчанию "*")
    @Value("\${app.cors.allowed-origins:*}")
    private lateinit var allowedOrigins: Array<String>

    override fun configureMessageBroker(config: MessageBrokerRegistry) {
        config.enableSimpleBroker("/topic")
        config.setApplicationDestinationPrefixes("/app")
    }

    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        // Подключаем наши разрешенные домены (spread operator * распаковывает массив)
        registry.addEndpoint("/ws-taxi").setAllowedOriginPatterns(*allowedOrigins).withSockJS()
        registry.addEndpoint("/ws-taxi").setAllowedOriginPatterns(*allowedOrigins)
    }
}