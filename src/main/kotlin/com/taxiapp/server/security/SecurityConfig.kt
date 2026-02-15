package com.taxiapp.server.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
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
    private val jwtAuthFilter: JwtAuthFilter
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
            .authorizeHttpRequests { auth ->
                auth
                    // 1. Публичные API (доступны всем)
                    .requestMatchers(
                        "/api/v1/auth/**",
                        "/api/v1/public/**",
                        "/ws-taxi/**",
                        
                        // --- ВАЖНО: Разрешаем доступ к странице "Фейковой оплаты" без токена ---
                        "/api/v1/payments/mock-gateway/**" 
                    ).permitAll()

                    // 2. Статические ресурсы (React build, файлы)
                    .requestMatchers(
                        "/",
                        "/index.html",
                        "/driver-register",
                        "/login",
                        "/dashboard/**",
                        "/assets/**",
                        "/favicon.ico",
                        "/*.png",
                        "/*.jpg",
                        "/*.svg",
                        "/*.json",
                        "/*.js",
                        "/*.css",
                        
                        "/images/**",
                        "/uploads/**"
                    ).permitAll()

                    // 3. !!! НАСТРОЙКА ДОСТУПА ПО РОЛЯМ !!!
                    
                    // --- НОВАЯ СЕКЦИЯ: ПЛАТЕЖИ ---
                    // Инициировать и проверять оплату могут Водители и Админы
                    .requestMatchers("/api/v1/payments/**").hasAnyAuthority(
                        "ROLE_DRIVER", "DRIVER",
                        "ROLE_ADMINISTRATOR", "ADMINISTRATOR"
                    )
                    // -----------------------------

                    .requestMatchers("/api/v1/admin/**").hasAnyAuthority(
                        "ROLE_ADMINISTRATOR", "ADMINISTRATOR", 
                        "ROLE_DISPATCHER", "DISPATCHER",
                        "ROLE_ADMIN", "ADMIN"
                    )
                    .requestMatchers("/api/v1/driver/**").hasAnyAuthority(
                        "ROLE_DRIVER", "DRIVER",
                        "ROLE_ADMINISTRATOR", "ADMINISTRATOR"
                    )
                    .requestMatchers("/api/v1/client/**").hasAnyAuthority(
                        "ROLE_CLIENT", "CLIENT",
                        "ROLE_ADMINISTRATOR", "ADMINISTRATOR"
                    )

                    // 4. Все остальные запросы требуют любой авторизации
                    .anyRequest().authenticated()
            }
            .authenticationProvider(authenticationProvider())
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter::class.java)

        return http.build()
    }

    @Bean
    fun corsConfigurationSource(): UrlBasedCorsConfigurationSource {
        val configuration = CorsConfiguration()
        // Разрешаем все источники для удобства разработки
        configuration.allowedOriginPatterns = listOf("*")
        configuration.allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        configuration.allowedHeaders = listOf("*")
        configuration.allowCredentials = true
        
        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", configuration)
        return source
    }
}