package com.taxiapp.server.security

import com.taxiapp.server.repository.UserRepository
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.User
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

        var user = userRepository.findByUserLogin(usernameOrPhone).orElse(null)

        if (user == null) {
            println(">>> USER DETAILS SERVICE: За логіном не знайдено, шукаю за телефоном...")
            user = userRepository.findByUserPhone(usernameOrPhone)
                .orElseThrow { UsernameNotFoundException("User not found with: $usernameOrPhone") }
        }

        // --- ВАЖНЫЙ БЛОК: Формирование роли ---
        val roleName = "ROLE_" + user.role.name
        
        // ЛОГ ДЛЯ ДИАГНОСТИКИ 403 ОШИБКИ
        println(">>> AUTH DEBUG: User ID: ${user.id}, Role in DB: ${user.role}, ASSIGNING AUTHORITY: $roleName")
        
        val authorities = listOf(SimpleGrantedAuthority(roleName))

        return User(
            user.userLogin ?: user.userPhone ?: "unknown",
            user.passwordHash ?: "",
            authorities
        )
    }
}