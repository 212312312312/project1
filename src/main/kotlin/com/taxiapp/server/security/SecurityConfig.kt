package com.taxiapp.server.security

import com.taxiapp.server.model.enums.Role
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.AuthenticationProvider
import org.springframework.security.authentication.dao.DaoAuthenticationProvider
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
class SecurityConfig(
    private val jwtAuthFilter: JwtAuthFilter,
    private val userDetailsService: UserDetailsService
) {

    // --- ВИПРАВЛЕННЯ ТУТ ---
    // Додаємо шляхи БЕЗ v1, бо Android стукає на /api/auth/...
    private val publicEndpoints = arrayOf(
        "/api/auth/**",          // <--- ВАЖЛИВО! (Для логіну водія)
        "/api/v1/auth/**",       // (Про всяк випадок залишаємо v1)
        "/api/public/**",        
        "/api/v1/public/**",
        "/images/**",
        "/uploads/**",
        "/v3/api-docs/**",
        "/swagger-ui/**",
        "/swagger-ui.html"
    )
    // -----------------------

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .cors { it.configurationSource(corsConfigurationSource()) }
            .csrf { it.disable() }
            .authorizeHttpRequests { auth ->
                auth
                    // 1. Публічні (використовують масив publicEndpoints)
                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    .requestMatchers(*publicEndpoints).permitAll()

                    // --- СЕРВІСИ (Залишаємо як є, або дублюємо без v1 якщо треба) ---
                    .requestMatchers(HttpMethod.GET, "/api/v1/services/**", "/api/services/**").permitAll()
                    
                    .requestMatchers("/api/v1/services/**", "/api/services/**").hasAnyAuthority(
                        "ADMINISTRATOR", "ROLE_ADMINISTRATOR",
                        "DISPATCHER", "ROLE_DISPATCHER"
                    )

                    // 2. АДМИНКА
                    .requestMatchers("/api/v1/admin/**", "/api/admin/**")
                        .hasAnyAuthority(
                            "ADMINISTRATOR", "ROLE_ADMINISTRATOR",
                            "DISPATCHER", "ROLE_DISPATCHER"
                        )

                    // 3. КЛИЕНТ
                    .requestMatchers("/api/v1/client/**", "/api/client/**")
                        .hasAnyAuthority("CLIENT", "ROLE_CLIENT")

                    // 4. ВОДИТЕЛЬ
                    // Android стукає сюди для замовлень: /api/v1/driver/...
                    .requestMatchers("/api/v1/driver/**", "/api/driver/**")
                        .hasAnyAuthority("DRIVER", "ROLE_DRIVER")

                    .anyRequest().authenticated()
            }
            .sessionManagement {
                it.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            }
            .authenticationProvider(authenticationProvider())
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter::class.java)

        return http.build()
    }

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val configuration = CorsConfiguration()
        configuration.allowedOriginPatterns = listOf("*")
        configuration.allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
        configuration.allowedHeaders = listOf("*")
        configuration.allowCredentials = true
        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", configuration)
        return source
    }

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun authenticationManager(config: AuthenticationConfiguration): AuthenticationManager = config.authenticationManager

    @Bean
    fun authenticationProvider(): AuthenticationProvider {
        val provider = DaoAuthenticationProvider()
        provider.setUserDetailsService(userDetailsService)
        provider.setPasswordEncoder(passwordEncoder())
        return provider
    }
}