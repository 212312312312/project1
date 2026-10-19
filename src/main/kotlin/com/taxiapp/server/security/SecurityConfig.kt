package com.taxiapp.server.security

import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.dao.DaoAuthenticationProvider
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
@EnableMethodSecurity
class SecurityConfig(
    private val userDetailsServiceImpl: UserDetailsServiceImpl,
    private val jwtAuthFilter: JwtAuthFilter,
    @Value("\${app.cors.allowed-origins:https://admin.unitua.com,http://localhost:5173,http://localhost:3000}")
    private val allowedOriginsStr: String
) {

    @Bean
    fun passwordEncoder(): PasswordEncoder {
        return BCryptPasswordEncoder()
    }

    @Bean
    fun authenticationManager(authConfig: AuthenticationConfiguration): AuthenticationManager {
        return authConfig.authenticationManager
    }

    @Bean
    fun authenticationProvider(): DaoAuthenticationProvider {
        val authProvider = DaoAuthenticationProvider()
        authProvider.setUserDetailsService(userDetailsServiceImpl)
        authProvider.setPasswordEncoder(passwordEncoder())
        return authProvider
    }

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .cors { it.configurationSource(corsConfigurationSource()) }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }

            .exceptionHandling { exceptions ->
                exceptions.authenticationEntryPoint { _, response, _ ->
                    response.status = HttpServletResponse.SC_UNAUTHORIZED
                    response.setHeader("WWW-Authenticate", "Bearer")
                    response.contentType = "application/json;charset=UTF-8"
                    response.writer.write("""{"error": "UNAUTHORIZED", "message": "Authentication is required"}""")
                }
            }

            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                    // ВАЖНО: Эндпоинты внутри /api/v1/auth/, которые требуют авторизации
                    .requestMatchers(
                        "/api/v1/auth/client/link-phone",
                        "/api/v1/auth/fcm-token"
                    ).authenticated()

                    // 1. Публичные API (включая WebView-маршруты водителя: формы и отправку фотоконтроля)
                    .requestMatchers(
                        "/api/v1/auth/**",
                        "/api/v1/public/**",
                        "/ws-taxi/**",
                        "/api/v1/payments/callback",
                        "/api/v1/driver/forms/**",
                        "/api/v1/photo-control/driver/*/submit",
                        "/error",
                        "/api/v1/support/webhook"
                    ).permitAll()

                    // 2. Статические ресурсы и SPA-маршруты
                    .requestMatchers(
                        "/", "/index.html", "/driver-register", "/driver-register/**", "/driver/**", "/login", "/dashboard/**",
                        "/assets/**", "/favicon.ico", "/*.png", "/*.jpg", "/*.svg",
                        "/*.json", "/*.js", "/*.css", "/images/**", "/uploads/**",
                        "/add-car", "/add-car/**",
                        "/photo-control/**", "/photo-upload/**"
                    ).permitAll()

                    // 3. Чтение настроек (иконка авто на карте), тарифов, аналитики и статистики отмен — доступно Админу и Диспетчеру
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/v1/admin/settings",
                        "/api/v1/admin/tariffs",
                        "/api/v1/admin/analytics/**",
                        "/api/v1/admin/stats/**"
                    ).hasAnyAuthority("ROLE_ADMINISTRATOR", "ROLE_DISPATCHER")

                    // 4. Изменение настроек, тарифов, секторов, промокодов и управление диспетчерами — строго ТОЛЬКО АДМИНИСТРАТОР
                    .requestMatchers(
                        "/api/v1/admin/dispatchers/**",
                        "/api/v1/admin/settings/**",
                        "/api/v1/admin/tariffs/**",
                        "/api/v1/admin/sectors/**",
                        "/api/v1/admin/services/**",
                        "/api/v1/admin/promos/**",
                        "/api/v1/admin/promocodes/**"
                    ).hasAuthority("ROLE_ADMINISTRATOR")

                    // 5. Общие разделы Админа и Диспетчера (заказы, клиенты, водители, саппорт, фотоконтроль, выплаты)
                    .requestMatchers(
                        "/api/v1/admin/**",
                        "/api/v1/support/admin/**",
                        "/api/v1/photo-control/admin/**",
                        "/api/v1/photo-control/request"
                    ).hasAnyAuthority("ROLE_ADMINISTRATOR", "ROLE_DISPATCHER")

                    // 6. Платежи, Водители и Клиенты
                    .requestMatchers("/api/v1/payments/**").hasAnyAuthority(
                        "ROLE_DRIVER", "ROLE_ADMINISTRATOR", "ROLE_CLIENT"
                    )
                    .requestMatchers(
                        "/api/v1/driver/**",
                        "/api/v1/photo-control/driver/**"
                    ).hasAnyAuthority("ROLE_DRIVER", "ROLE_ADMINISTRATOR")
                    .requestMatchers("/api/v1/client/**").hasAnyAuthority(
                        "ROLE_CLIENT", "ROLE_ADMINISTRATOR"
                    )

                    // 7. Все остальные (включая /api/v1/orders/** и /api/v1/chat/**)
                    .anyRequest().authenticated()
            }
            .authenticationProvider(authenticationProvider())
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter::class.java)

        return http.build()
    }

    @Bean
    fun corsConfigurationSource(): UrlBasedCorsConfigurationSource {
        val origins = allowedOriginsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val configuration = CorsConfiguration()
        configuration.allowedOriginPatterns = origins
        configuration.allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        configuration.allowedHeaders = listOf("*")
        configuration.allowCredentials = true

        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", configuration)
        return source
    }
}