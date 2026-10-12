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

    private val wsLogger = org.slf4j.LoggerFactory.getLogger(WebSocketConfig::class.java)

    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        val origins = allowedOriginsStr.split(",").map { it.trim() }.toTypedArray()

        // Перехватчик токена из параметров URL handshake (?token=... или ?access_token=...)
        val queryInterceptor = object : org.springframework.web.socket.server.HandshakeInterceptor {
            override fun beforeHandshake(
                request: org.springframework.http.server.ServerHttpRequest,
                response: org.springframework.http.server.ServerHttpResponse,
                wsHandler: org.springframework.web.socket.WebSocketHandler,
                attributes: MutableMap<String, Any>
            ): Boolean {
                val query = request.uri.query
                if (!query.isNullOrBlank()) {
                    val params = query.split("&").associate {
                        val parts = it.split("=")
                        parts[0] to (parts.getOrNull(1) ?: "")
                    }
                    val t = params["token"] ?: params["accessToken"] ?: params["access_token"]
                    if (!t.isNullOrBlank()) {
                        attributes["token"] = t
                    }
                }
                val authHeader = request.headers.getFirst("Authorization") ?: request.headers.getFirst("authorization")
                if (!authHeader.isNullOrBlank()) {
                    attributes["authHeader"] = authHeader
                }
                return true
            }

            override fun afterHandshake(
                request: org.springframework.http.server.ServerHttpRequest,
                response: org.springframework.http.server.ServerHttpResponse,
                wsHandler: org.springframework.web.socket.WebSocketHandler,
                exception: Exception?
            ) {}
        }

        registry.addEndpoint("/ws-taxi", "/ws-taxi/websocket")
            .setAllowedOriginPatterns(*origins)
            .addInterceptors(queryInterceptor)
            .withSockJS()

        registry.addEndpoint("/ws-taxi", "/ws-taxi/websocket")
            .setAllowedOriginPatterns(*origins)
            .addInterceptors(queryInterceptor)
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

                    // 1. Проверяем все возможные варианты названий заголовков токена
                    val headerCandidates = listOf(
                        "Authorization", "authorization", 
                        "token", "Token", 
                        "access_token", "accessToken", 
                        "X-Authorization", "passcode"
                    )
                    var rawToken: String? = null
                    for (headerName in headerCandidates) {
                        val h = accessor.getFirstNativeHeader(headerName)
                        if (!h.isNullOrBlank()) {
                            rawToken = h
                            break
                        }
                    }

                    // 2. Проверяем системный passcode фрейма CONNECT
                    if (rawToken.isNullOrBlank() && !accessor.passcode.isNullOrBlank()) {
                        rawToken = accessor.passcode
                    }

                    // 3. Проверяем атрибуты сессии из HTTP handshake (URL query params)
                    if (rawToken.isNullOrBlank()) {
                        rawToken = (accessor.sessionAttributes?.get("token") as? String)
                            ?: (accessor.sessionAttributes?.get("authHeader") as? String)
                    }

                    if (!rawToken.isNullOrBlank()) {
                        val token = if (rawToken.startsWith("Bearer ", ignoreCase = true)) {
                            rawToken.substring(7).trim()
                        } else {
                            rawToken.trim()
                        }

                        try {
                            val username = jwtUtils.extractUsername(token)
                            if (username != null) {
                                val userDetails = userDetailsService.loadUserByUsername(username)
                                if (jwtUtils.validateToken(token, userDetails)) {
                                    val authentication = UsernamePasswordAuthenticationToken(
                                        userDetails, null, userDetails.authorities
                                    )
                                    accessor.user = authentication
                                    wsLogger.info(">>> [WS Auth OK] Успішна авторизація для: $username")
                                    return message
                                }
                            }
                        } catch (e: Exception) {
                            wsLogger.warn(">>> [WS Auth] Недійсний токен: ${e.message}")
                            throw org.springframework.messaging.MessageDeliveryException("Invalid Token")
                        }
                    }

                    wsLogger.warn(">>> [WS Auth] Вхід відхилено: токен не знайдено! Headers: ${accessor.toNativeHeaderMap()}, sessionAttr: ${accessor.sessionAttributes}")
                    throw org.springframework.messaging.MessageDeliveryException("Unauthorized: Access Denied")
                }
                return message
            }
        })
    }
}