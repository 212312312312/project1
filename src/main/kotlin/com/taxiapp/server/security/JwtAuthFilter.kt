package com.taxiapp.server.security

import io.jsonwebtoken.ExpiredJwtException
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class JwtAuthFilter(
    private val jwtUtils: JwtUtils,
    private val userDetailsService: UserDetailsService
) : OncePerRequestFilter() {

    private val jwtLogger = LoggerFactory.getLogger(JwtAuthFilter::class.java)

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.servletPath

        // ВАЖНО: Эти 2 эндпоинта внутри /api/v1/auth/ требуют Principal (авторизованного пользователя)!
        if (path == "/api/v1/auth/client/link-phone" || path == "/api/v1/auth/fcm-token") {
            return false
        }

        // Публичные гео-эндпоинты и справочники не требуют загрузки UserDetails из БД при каждом запросе
        // (исключение: /api/v1/public/calculate-price, где Principal используется для персональной скидки клиента)
        if (path == "/api/v1/public/nearby-suggestions" ||
            path == "/api/v1/public/reverse-geocode" ||
            path == "/api/v1/public/route" ||
            path == "/api/v1/public/sectors" ||
            path == "/api/v1/public/tariffs" ||
            path.startsWith("/api/v1/public/settings/")
        ) {
            return true
        }

        return path.startsWith("/api/v1/auth/") ||
               path.startsWith("/assets/") ||
               path.startsWith("/ws") ||
               path.startsWith("/topic/") ||
               path.startsWith("/app/") ||
               path == "/driver-register" ||
               path == "/index.html" ||
               path == "/" ||
               path.endsWith(".html") ||
               path.endsWith(".js") ||
               path.endsWith(".css") ||
               path.endsWith(".svg") ||
               path.endsWith(".png") ||
               path.endsWith(".ico")
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        var jwtToken: String? = null

        // 1. Пытаемся достать токен из HttpOnly Cookie (для Диспетчерской)
        val cookies = request.cookies
        if (cookies != null) {
            val accessCookie = cookies.find { it.name == "accessToken" }
            if (accessCookie != null) {
                jwtToken = accessCookie.value
            }
        }

        // 2. Если в куках пусто, ищем в заголовке Authorization (для мобильных приложений)
        if (jwtToken == null) {
            val authHeader: String? = request.getHeader("Authorization")
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                jwtToken = authHeader.substring(7).trim()
            }
        }

        // 3. Достаем токен из параметра URL ?token=... ТОЛЬКО для специализированных WebView-маршрутов
        if (jwtToken == null && (request.servletPath.startsWith("/api/v1/driver/forms/") || request.servletPath.startsWith("/add-car"))) {
            val tokenParam = request.getParameter("token")
            if (!tokenParam.isNullOrEmpty()) {
                jwtToken = tokenParam.trim()
            }
        }

        if (jwtToken.isNullOrEmpty() || jwtToken == "null" || jwtToken == "undefined") {
            filterChain.doFilter(request, response)
            return
        }

        try {
            val username = jwtUtils.extractUsername(jwtToken)

            if (username != null && SecurityContextHolder.getContext().authentication == null) {
                val userDetails: UserDetails = userDetailsService.loadUserByUsername(username)

                // ЗАЩИТА: Проверяем, что аккаунт НЕ заблокирован (постоянно или временно)
                if (!userDetails.isAccountNonLocked || !userDetails.isEnabled) {
                    response.status = HttpServletResponse.SC_FORBIDDEN
                    response.contentType = "application/json;charset=UTF-8"
                    response.writer.write("""{"error": "ACCOUNT_BLOCKED", "message": "Ваш акаунт заблоковано"}""")
                    return
                }

                if (jwtUtils.validateToken(jwtToken, userDetails)) {
                    val authToken = UsernamePasswordAuthenticationToken(
                        userDetails,
                        null,
                        userDetails.authorities
                    )
                    authToken.details = WebAuthenticationDetailsSource().buildDetails(request)
                    SecurityContextHolder.getContext().authentication = authToken
                }
            }
        } catch (e: ExpiredJwtException) {
            jwtLogger.debug(">>> JWT FILTER: Token EXPIRED for: ${request.requestURI}")
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            response.setHeader("WWW-Authenticate", "Bearer")
            response.contentType = "application/json;charset=UTF-8"
            response.writer.write("""{"error": "TOKEN_EXPIRED", "message": "Access token is expired"}""")
            return
        } catch (e: Exception) {
            jwtLogger.debug(">>> JWT FILTER: Error parsing token: ${e.message}")
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            response.setHeader("WWW-Authenticate", "Bearer")
            response.contentType = "application/json;charset=UTF-8"
            response.writer.write("""{"error": "INVALID_TOKEN", "message": "Invalid access token"}""")
            return
        }

        filterChain.doFilter(request, response)
    }
}