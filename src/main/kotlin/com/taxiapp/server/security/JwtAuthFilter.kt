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

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            // ЛОГ: Нет заголовка
            // println(">>> JWT FILTER: No Authorization header for ${request.requestURI}")
            filterChain.doFilter(request, response)
            return
        }

        val jwtToken = authHeader.substring(7).trim()

        try {
            if (jwtToken.isEmpty()) {
                println(">>> JWT FILTER: Token is empty")
                filterChain.doFilter(request, response)
                return
            }

            val username = jwtUtils.extractUsername(jwtToken)
            // ЛОГ: Юзернейм из токена
            println(">>> JWT FILTER: Extracted username: $username")

            if (username != null && SecurityContextHolder.getContext().authentication == null) {
                val userDetails: UserDetails = userDetailsService.loadUserByUsername(username)

                if (jwtUtils.validateToken(jwtToken, userDetails)) {
                    // ЛОГ: Успешная валидация
                    println(">>> JWT FILTER: Token VALID. Authenticating user: ${userDetails.username}")

                    val authToken = UsernamePasswordAuthenticationToken(
                        userDetails,
                        null,
                        userDetails.authorities
                    )
                    authToken.details = WebAuthenticationDetailsSource().buildDetails(request)
                    SecurityContextHolder.getContext().authentication = authToken
                } else {
                    println(">>> JWT FILTER: Token INVALID for user: $username")
                }
            }
        } catch (e: ExpiredJwtException) {
            println(">>> JWT FILTER: Token EXPIRED for: ${request.requestURI}")
        } catch (e: Exception) {
            println(">>> JWT FILTER: Error parsing token: ${e.message}")
            e.printStackTrace()
        }

        filterChain.doFilter(request, response)
    }
}