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

        // 1. Спроба знайти за Логіном
        var user = userRepository.findByUserLogin(usernameOrPhone).orElse(null)

        // 2. Якщо не знайдено - спроба знайти за Телефоном
        if (user == null) {
            println(">>> USER DETAILS SERVICE: За логіном не знайдено, шукаю за телефоном...")
            user = userRepository.findByUserPhone(usernameOrPhone)
                .orElseThrow { UsernameNotFoundException("User not found with: $usernameOrPhone") }
        }

        println(">>> USER DETAILS SERVICE: Знайдено ID: ${user.id}, Hash: ${user.passwordHash}")

        // !!! ВИПРАВЛЕНО ТУТ !!!
        // Додаємо префікс "ROLE_", щоб працювала анотація @PreAuthorize("hasRole('DRIVER')")
        val roleName = "ROLE_" + user.role.name // Буде "ROLE_DRIVER"
        val authorities = listOf(SimpleGrantedAuthority(roleName))

        return User(
            user.userLogin ?: user.userPhone,
            user.passwordHash ?: "",
            authorities
        )
    }
}