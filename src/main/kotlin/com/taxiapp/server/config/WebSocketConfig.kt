package com.taxiapp.server.config

import com.taxiapp.server.security.JwtUtils
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.simp.config.ChannelRegistration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.ChannelInterceptor
import org.springframework.messaging.support.MessageHeaderAccessor
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer

@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig(
    private val jwtUtils: JwtUtils,
    private val userDetailsService: UserDetailsService
) : WebSocketMessageBrokerConfigurer {

    @Value("\${app.cors.allowed-origins:*}")
    private lateinit var allowedOrigins: Array<String>

    override fun configureMessageBroker(config: MessageBrokerRegistry) {
        config.enableSimpleBroker("/topic")
        config.setApplicationDestinationPrefixes("/app")
    }

    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        registry.addEndpoint("/ws-taxi").setAllowedOriginPatterns(*allowedOrigins).withSockJS()
        registry.addEndpoint("/ws-taxi").setAllowedOriginPatterns(*allowedOrigins)
    }

    // --- ЗАЩИТА: Авторизация WebSocket-подключений на уровне фреймов CONNECT ---
    override fun configureClientInboundChannel(registration: ChannelRegistration) {
        registration.interceptors(object : ChannelInterceptor {
            override fun preSend(message: Message<*>, channel: MessageChannel): Message<*>? {
                val accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor::class.java)
                
                if (accessor != null && StompCommand.CONNECT == accessor.command) {
                    val authHeader = accessor.getFirstNativeHeader("Authorization")
                    
                    if (authHeader != null && authHeader.startsWith("Bearer ")) {
                        val token = authHeader.substring(7).trim()
                        try {
                            val username = jwtUtils.extractUsername(token)
                            if (username != null) {
                                val userDetails = userDetailsService.loadUserByUsername(username)
                                if (jwtUtils.validateToken(token, userDetails)) {
                                    // Успешно авторизуем сокет-сессию внутри Spring Messaging
                                    val authentication = UsernamePasswordAuthenticationToken(
                                        userDetails, null, userDetails.authorities
                                    )
                                    accessor.user = authentication
                                }
                            }
                        } catch (e: Exception) {
                            // Токен невалиден — сессия останется неавторизованной и сбросится
                        }
                    }
                }
                return message
            }
        })
    }
}