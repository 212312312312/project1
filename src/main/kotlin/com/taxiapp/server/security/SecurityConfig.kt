package com.taxiapp.server.security

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
@EnableMethodSecurity(prePostEnabled = true) // Це вмикає @PreAuthorize в контролерах
class SecurityConfig(
    private val jwtAuthFilter: JwtAuthFilter,
    private val userDetailsService: UserDetailsService
) {

    // Сюди додаємо шляхи, які не потребують авторизації
    private val publicEndpoints = arrayOf(
        "/api/auth/**",
        "/api/v1/auth/**",
        "/api/public/**",
        "/api/v1/public/**", // <-- ЦЕ ВАЖЛИВО! Це дозволяє доступ до /calculate-price
        "/images/**",
        "/uploads/**",
        "/v3/api-docs/**",
        "/swagger-ui/**",
        "/swagger-ui.html"
    )

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .cors { it.configurationSource(corsConfigurationSource()) }
            .csrf { it.disable() }
            .authorizeHttpRequests { auth ->
                auth
                    // 1. Публічні ендпоінти
                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll() // Дозволяємо CORS pre-flight запити
                    .requestMatchers(*publicEndpoints).permitAll()
                    
                    // !!! ВАЖЛИВО: ДОЗВОЛЯЄМО WEBSOCKET ПІДКЛЮЧЕННЯ БЕЗ ТОКЕНА !!!
                    .requestMatchers("/ws-taxi/**").permitAll()
                    // !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
                    .requestMatchers("/api/v1/driver/forms/**").permitAll()
                    .requestMatchers("/api/v1/driver/cars/add").permitAll()

                    // 2. СЕРВІСИ
                    .requestMatchers(HttpMethod.GET, "/api/v1/services/**", "/api/services/**").permitAll()
                    .requestMatchers("/api/v1/services/**", "/api/services/**").hasAnyAuthority(
                        "ADMINISTRATOR", "ROLE_ADMINISTRATOR",
                        "DISPATCHER", "ROLE_DISPATCHER"
                    )

                    // 3. АДМІНКА
                    // Тут ми пускаємо і Адмінів, і Диспетчерів.
                    // А конкретний доступ до /settings обмежуємо вже в контролері через @PreAuthorize
                    .requestMatchers("/api/v1/admin/**", "/api/admin/**")
                        .hasAnyAuthority(
                            "ADMINISTRATOR", "ROLE_ADMINISTRATOR",
                            "DISPATCHER", "ROLE_DISPATCHER"
                        )

                    // 4. КЛІЄНТ
                    .requestMatchers("/api/v1/client/**", "/api/client/**")
                        .hasAnyAuthority("CLIENT", "ROLE_CLIENT")

                    // 5. ВОДІЙ (Android)
                    .requestMatchers("/api/v1/driver/**", "/api/driver/**")
                        .hasAnyAuthority("DRIVER", "ROLE_DRIVER")

                    // Всі інші запити вимагають авторизації
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
        configuration.allowedOriginPatterns = listOf("*") // Дозволяємо всі домени (для розробки)
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