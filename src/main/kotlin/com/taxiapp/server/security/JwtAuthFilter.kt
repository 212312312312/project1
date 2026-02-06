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
        val authHeader: String? = request.getHeader("Authorization")

        // Если заголовка нет или он не начинается с Bearer — пропускаем
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response)
            return
        }

        // ВАЖНОЕ ИСПРАВЛЕНИЕ: 
        // 1. substring(7) убирает "Bearer "
        // 2. trim() убирает случайные пробелы в начале или конце, из-за которых падала ошибка Base64
        val jwtToken = authHeader.substring(7).trim()

        try {
            // Если токен пустой после обрезки — пропускаем
            if (jwtToken.isEmpty()) {
                filterChain.doFilter(request, response)
                return
            }

            val username = jwtUtils.extractUsername(jwtToken)

            if (username != null && SecurityContextHolder.getContext().authentication == null) {
                val userDetails: UserDetails = userDetailsService.loadUserByUsername(username)

                if (jwtUtils.validateToken(jwtToken, userDetails)) {
                    
                    // --- ЛОГ ДЛЯ ОТЛАДКИ ---
                    // println(">>> JWT FILTER: User authenticated: ${userDetails.username}")
                    // -----------------------

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
            // Токен просрочен — не считаем это критической ошибкой сервера (не 500), просто не пускаем
            println(">>> JWT FILTER: Token expired for: ${request.requestURI}")
        } catch (e: Exception) {
            // Ошибка парсинга (например, неверный формат)
            println(">>> JWT FILTER: Error parsing token: ${e.message}")
        }

        filterChain.doFilter(request, response)
    }
}