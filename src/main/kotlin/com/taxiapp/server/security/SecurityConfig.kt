package com.taxiapp.server.security

import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpMethod
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
            
            .exceptionHandling { exceptions ->
                exceptions.authenticationEntryPoint { request, response, authException ->
                    response.status = HttpServletResponse.SC_UNAUTHORIZED
                    response.setHeader("WWW-Authenticate", "Bearer")
                    response.contentType = "application/json;charset=UTF-8"
                    response.writer.write("""{"error": "UNAUTHORIZED", "message": "Authentication is required"}""")
                }
            }
            
            .authorizeHttpRequests { auth ->
                auth
                    // Явно разрешаем все CORS-префлайты (OPTIONS)
                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                    // 1. Публичные API (доступны всем)
                    .requestMatchers(
                        "/api/v1/auth/**",
                        "/api/v1/public/**",
                        "/ws-taxi/**",
                        "/api/v1/payments/mock-gateway/**",
                        "/api/v1/payments/callback",
                        "/api/v1/driver/forms/**",
                        "/api/v1/photo-control/driver/*/submit",
                        "/error"
                    ).permitAll()

                    // 2. Статические ресурсы и WebView-страницы (React build)
                    .requestMatchers(
                        "/", "/index.html", "/driver-register", "/driver/**", "/login", "/dashboard/**", 
                        "/assets/**", "/favicon.ico", "/*.png", "/*.jpg", "/*.svg", 
                        "/*.json", "/*.js", "/*.css", "/images/**", "/uploads/**", "/add-car/**",
                        "/photo-control/**", "/photo-upload/**"
                    ).permitAll()

                    // 3. Доступ по ролям
                    .requestMatchers("/api/v1/payments/**").hasAnyAuthority(
                        "ROLE_DRIVER", "ROLE_ADMINISTRATOR", "ROLE_CLIENT"
                    )
                    .requestMatchers("/api/v1/admin/**", "/api/v1/photo-control/admin/**", "/api/v1/photo-control/request").hasAnyAuthority(
                        "ROLE_ADMINISTRATOR", "ROLE_DISPATCHER"
                    )
                    .requestMatchers("/api/v1/driver/**").hasAnyAuthority(
                        "ROLE_DRIVER", "ROLE_ADMINISTRATOR"
                    )
                    .requestMatchers("/api/v1/client/**").hasAnyAuthority(
                        "ROLE_CLIENT", "ROLE_ADMINISTRATOR"
                    )

                    // 4. Все остальные
                    .anyRequest().authenticated()
            }
            .authenticationProvider(authenticationProvider())
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter::class.java)

        return http.build()
    }

    @Bean
    fun corsConfigurationSource(): UrlBasedCorsConfigurationSource {
        val configuration = CorsConfiguration()
        configuration.allowedOriginPatterns = listOf("*")
        configuration.allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        configuration.allowedHeaders = listOf("*")
        configuration.allowCredentials = true
        
        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", configuration)
        return source
    }
}