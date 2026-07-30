package com.taxiapp.server

import com.taxiapp.server.model.enums.Role
import com.taxiapp.server.model.user.User
import com.taxiapp.server.repository.UserRepository
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.scheduling.annotation.EnableScheduling // <--- ВАЖЛИВИЙ ІМПОРТ
import org.springframework.security.crypto.password.PasswordEncoder

@SpringBootApplication
@EnableScheduling // <--- ЦЕ ВКЛЮЧАЄ ТАЙМЕР ДЛЯ СКИДАННЯ ЗАМОВЛЕНЬ
class ServerApplication {

    @Bean
    fun initDefaultAdmin(
        userRepository: UserRepository,
        passwordEncoder: PasswordEncoder
    ): CommandLineRunner {
        return CommandLineRunner {
            val adminUsername = "admin"
            
            if (userRepository.findByUserLogin(adminUsername).isEmpty) {
                println("Створення користувача Адміністратора за замовчуванням...")
                val admin = User().apply {
                    this.userLogin = adminUsername
                    this.fullName = "Головний Адміністратор"
                    this.passwordHash = passwordEncoder.encode("adminpass") 
                    this.role = Role.ADMINISTRATOR
                    this.isBlocked = false
                }
                userRepository.save(admin)
                println("Користувач '$adminUsername' (ADMINISTRATOR) створений з паролем 'adminpass'")
            }
        }
    }
}

fun main(args: Array<String>) {
    runApplication<ServerApplication>(*args)
}