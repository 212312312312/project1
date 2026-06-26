package com.taxiapp.server.security

import io.jsonwebtoken.ExpiredJwtException
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
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

        // Если токена нет вообще — просто идем к следующему фильтру
        if (jwtToken.isNullOrEmpty()) {
            filterChain.doFilter(request, response)
            return
        }

        // Тот самый пропущенный try {, который собирает ошибки валидации токена
        try {
            val username = jwtUtils.extractUsername(jwtToken)

            if (username != null && SecurityContextHolder.getContext().authentication == null) {
                val userDetails: UserDetails = userDetailsService.loadUserByUsername(username)

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
            println(">>> JWT FILTER: Token EXPIRED for: ${request.requestURI}")
            response.status = HttpServletResponse.SC_UNAUTHORIZED 
            response.setHeader("WWW-Authenticate", "Bearer")
            response.contentType = "application/json;charset=UTF-8"
            response.writer.write("""{"error": "TOKEN_EXPIRED", "message": "Access token is expired"}""")
            return 
        } catch (e: Exception) {
            println(">>> JWT FILTER: Error parsing token: ${e.message}")
            response.status = HttpServletResponse.SC_UNAUTHORIZED 
            response.setHeader("WWW-Authenticate", "Bearer")
            response.contentType = "application/json;charset=UTF-8"
            response.writer.write("""{"error": "INVALID_TOKEN", "message": "Invalid access token"}""")
            return 
        }

        filterChain.doFilter(request, response)
    }
}