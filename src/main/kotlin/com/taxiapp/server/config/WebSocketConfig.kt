package com.taxiapp.server.config

import com.taxiapp.server.model.user.User
import com.taxiapp.server.security.JwtUtils
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.MessageDeliveryException
import org.springframework.messaging.simp.config.ChannelRegistration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.ChannelInterceptor
import org.springframework.messaging.support.MessageHeaderAccessor
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration

@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig(
    private val jwtUtils: JwtUtils,
    private val userDetailsService: UserDetailsService
) : WebSocketMessageBrokerConfigurer {

    @Value("\${app.cors.allowed-origins:https://admin.unitua.com,http://localhost:5173,http://localhost:3000}")
    private lateinit var allowedOriginsStr: String

    private val wsLogger = LoggerFactory.getLogger(WebSocketConfig::class.java)

    override fun configureMessageBroker(config: MessageBrokerRegistry) {
        config.enableSimpleBroker("/topic")
        config.setApplicationDestinationPrefixes("/app")
    }

    override fun configureWebSocketTransport(registration: WebSocketTransportRegistration) {
        // Защита от OOM на медленных мобильных соединениях
        registration.setMessageSizeLimit(64 * 1024)       // 64 KB макс размер фрейма
        registration.setSendTimeLimit(10_000)             // 10 сек таймаут отправки
        registration.setSendBufferSizeLimit(512 * 1024)   // 512 KB буфер на клиента
    }

    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        val origins = allowedOriginsStr.split(",").map { it.trim() }.toTypedArray()

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
                    ?: return message

                when (accessor.command) {
                    StompCommand.CONNECT -> {
                        val existingUser = accessor.user
                        if (existingUser is Authentication && existingUser.isAuthenticated) {
                            return message
                        }

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

                        if (rawToken.isNullOrBlank() && !accessor.passcode.isNullOrBlank()) {
                            rawToken = accessor.passcode
                        }

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
                                    if (userDetails.isAccountNonLocked && jwtUtils.validateToken(token, userDetails)) {
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
                                throw MessageDeliveryException("Invalid Token")
                            }
                        }

                        wsLogger.warn(">>> [WS Auth] Вхід відхилено: токен не знайдено!")
                        throw MessageDeliveryException("Unauthorized: Access Denied")
                    }

                    StompCommand.SUBSCRIBE -> {
                        val auth = accessor.user as? Authentication
                            ?: throw MessageDeliveryException("Unauthorized subscribe")
                        val principalUser = auth.principal as? User
                            ?: throw MessageDeliveryException("Invalid principal")
                        val roles = auth.authorities.map { it.authority }.toSet()
                        val isAdminOrDispatcher = "ROLE_ADMINISTRATOR" in roles || "ROLE_DISPATCHER" in roles
                        val isDriver = "ROLE_DRIVER" in roles
                        val dest = accessor.destination ?: ""

                        // 1. Защита админских и саппорт-топиков
                        if ((dest.startsWith("/topic/admin") || dest.startsWith("/app/admin") || dest.startsWith("/topic/support")) && !isAdminOrDispatcher) {
                            throw MessageDeliveryException("Forbidden: Admin/Dispatcher topic")
                        }

                        // 2. Защита общего эфира водителей
                        if ((dest.startsWith("/topic/drivers/ether") || dest.startsWith("/app/drivers/ether")) && !isDriver && !isAdminOrDispatcher) {
                            throw MessageDeliveryException("Forbidden: Driver ether topic")
                        }

                        // 3. Защита персональных топиков водителя (/topic/drivers/{id}/... или /topic/driver/{id})
                        val driverMatch = Regex("^/(?:topic|app)/drivers?/(\\d+)(?:/.*)?$").find(dest)
                        if (driverMatch != null && !isAdminOrDispatcher) {
                            val targetDriverId = driverMatch.groupValues[1].toLongOrNull()
                            if (!isDriver || principalUser.id != targetDriverId) {
                                throw MessageDeliveryException("Forbidden: Another driver's topic")
                            }
                        }

                        // 4. Защита персональных топиков клиента (/topic/clients/{id}/...)
                        val clientMatch = Regex("^/topic/clients/(\\d+)(?:/.*)?$").find(dest)
                        if (clientMatch != null && !isAdminOrDispatcher) {
                            val targetClientId = clientMatch.groupValues[1].toLongOrNull()
                            if (principalUser.id != targetClientId) {
                                throw MessageDeliveryException("Forbidden: Another client's topic")
                            }
                        }
                    }

                    StompCommand.SEND -> {
                        val dest = accessor.destination ?: ""
                        // Клиентам разрешено отправлять сообщения только в /app/**, но НЕ напрямую в /topic/**!
                        if (dest.startsWith("/topic/")) {
                            throw MessageDeliveryException("Forbidden: Direct publishing to /topic is prohibited")
                        }
                    }

                    else -> {}
                }
                return message
            }
        })
    }
}