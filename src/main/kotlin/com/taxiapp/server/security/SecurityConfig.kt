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
                    // 1. Публічні API (доступні всім)
                    .requestMatchers(
                        "/api/v1/auth/**",
                        "/api/v1/public/**",
                        "/ws-taxi/**"
                    ).permitAll()

                    // 2. Статичні ресурси (React build) та завантажені файли
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
                        
                        // Дозволяємо доступ до картинок та папки uploads
                        "/images/**",
                        "/uploads/**"
                    ).permitAll()

                    // 3. !!! НАЛАШТУВАННЯ ДОСТУПУ ПО РОЛЯХ !!!
                    // Враховуємо префікс ROLE_, який є в базі
                    .requestMatchers("/api/v1/admin/**").hasAnyAuthority(
                        "ROLE_ADMINISTRATOR", "ADMINISTRATOR", 
                        "ROLE_DISPATCHER", "DISPATCHER",
                        "ROLE_ADMIN", "ADMIN" // На всяк випадок
                    )
                    .requestMatchers("/api/v1/driver/**").hasAnyAuthority(
                        "ROLE_DRIVER", "DRIVER",
                        "ROLE_ADMINISTRATOR", "ADMINISTRATOR"
                    )
                    .requestMatchers("/api/v1/client/**").hasAnyAuthority(
                        "ROLE_CLIENT", "CLIENT",
                        "ROLE_ADMINISTRATOR", "ADMINISTRATOR"
                    )

                    // 4. Всі інші запити вимагають авторизації (будь-якої)
                    .anyRequest().authenticated()
            }
            .authenticationProvider(authenticationProvider())
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter::class.java)

        return http.build()
    }

    @Bean
    fun corsConfigurationSource(): UrlBasedCorsConfigurationSource {
        val configuration = CorsConfiguration()
        // Дозволяємо всі джерела для зручності розробки
        configuration.allowedOriginPatterns = listOf("*")
        configuration.allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        configuration.allowedHeaders = listOf("*")
        configuration.allowCredentials = true
        
        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", configuration)
        return source
    }
}