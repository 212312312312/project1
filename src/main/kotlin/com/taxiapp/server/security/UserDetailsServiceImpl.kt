package com.taxiapp.server.security

import com.taxiapp.server.repository.UserRepository
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service

@Service
class UserDetailsServiceImpl(
    private val userRepository: UserRepository
) : UserDetailsService {

    override fun loadUserByUsername(usernameOrPhone: String): UserDetails {
        println(">>> USER DETAILS SERVICE: Шукаю користувача: $usernameOrPhone")

        // 1. Пытаемся найти по логину
        var user = userRepository.findByUserLogin(usernameOrPhone).orElse(null)

        // 2. Если не нашли, ищем по телефону
        if (user == null) {
            println(">>> USER DETAILS SERVICE: За логіном не знайдено, шукаю за телефоном...")
            user = userRepository.findByUserPhone(usernameOrPhone)
                .orElseThrow { UsernameNotFoundException("User not found with: $usernameOrPhone") }
        }

        // ЛОГ: Убеждаемся, что нашли именно того, кого нужно
        println(">>> AUTH DEBUG: Found User ID: ${user.id}, Role: ${user.role}")

        // --- ГЛАВНОЕ ИСПРАВЛЕНИЕ ---
        // Мы возвращаем саму сущность `user` из базы данных.
        // Так как твой класс User (и Driver) реализует интерфейс UserDetails,
        // Spring Security примет его, а контроллер сможет сделать (user as Driver).id
        return user
    }
}