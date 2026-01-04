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

        // Если заголовка нет или он неправильный — пропускаем фильтр
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response)
            return
        }

        val jwtToken = authHeader.substring(7)

        try {
            // Пытаемся достать имя пользователя. 
            // Если токен просрочен, здесь вылетит ExpiredJwtException
            val username = jwtUtils.extractUsername(jwtToken)

            if (username != null && SecurityContextHolder.getContext().authentication == null) {
                val userDetails: UserDetails = userDetailsService.loadUserByUsername(username)

                if (jwtUtils.validateToken(jwtToken, userDetails)) {
                    
                    // --- ЛОГ (ДІАГНОСТИКА) ---
                    println(">>> JWT FILTER: User found: ${userDetails.username}")
                    // -------------------------

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
            // ВАЖНО: Если токен просрочен, мы просто пишем лог и идем дальше.
            // Мы НЕ выбрасываем ошибку, чтобы сервер не упал с кодом 500.
            // Запрос пойдет дальше без авторизации.
            println(">>> JWT FILTER: Token expired for request: ${request.requestURI}")
        } catch (e: Exception) {
            // Любая другая ошибка валидации токена
            println(">>> JWT FILTER: Error parsing token: ${e.message}")
        }

        filterChain.doFilter(request, response)
    }
}