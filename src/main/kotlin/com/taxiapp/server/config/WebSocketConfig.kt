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

    @Value("\${app.cors.allowed-origins:https://admin.unitua.com,http://localhost:5173,http://localhost:3000}")
    private lateinit var allowedOriginsStr: String

    override fun configureMessageBroker(config: MessageBrokerRegistry) {
        config.enableSimpleBroker("/topic")
        config.setApplicationDestinationPrefixes("/app")
    }

    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        val origins = allowedOriginsStr.split(",").map { it.trim() }.toTypedArray()

        registry.addEndpoint("/ws-taxi").setAllowedOriginPatterns(*origins).withSockJS()
        registry.addEndpoint("/ws-taxi").setAllowedOriginPatterns(*origins)
    }

    override fun configureClientInboundChannel(registration: ChannelRegistration) {
        registration.interceptors(object : ChannelInterceptor {
            override fun preSend(message: Message<*>, channel: MessageChannel): Message<*>? {
                val accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor::class.java)

                if (accessor != null && StompCommand.CONNECT == accessor.command) {
                    val user = accessor.user
                    if (user is org.springframework.security.core.Authentication && user.isAuthenticated) {
                        return message
                    }

                    var authHeader = accessor.getFirstNativeHeader("Authorization")
                    if (authHeader == null) {
                        authHeader = accessor.getFirstNativeHeader("authorization")
                    }

                    if (authHeader != null && authHeader.startsWith("Bearer ")) {
                        val token = authHeader.substring(7).trim()
                        try {
                            val username = jwtUtils.extractUsername(token)
                            if (username != null) {
                                val userDetails = userDetailsService.loadUserByUsername(username)
                                if (jwtUtils.validateToken(token, userDetails)) {
                                    val authentication = UsernamePasswordAuthenticationToken(
                                        userDetails, null, userDetails.authorities
                                    )
                                    accessor.user = authentication
                                    return message
                                }
                            }
                        } catch (e: Exception) {
                            throw org.springframework.messaging.MessageDeliveryException("Invalid Token")
                        }
                    }
                    throw org.springframework.messaging.MessageDeliveryException("Unauthorized: Access Denied")
                }
                return message
            }
        })
    }
}