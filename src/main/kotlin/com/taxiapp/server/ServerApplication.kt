package com.taxiapp.server

import com.taxiapp.server.model.enums.Role
import com.taxiapp.server.model.user.User
import com.taxiapp.server.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.security.crypto.password.PasswordEncoder
import java.util.UUID

@SpringBootApplication
@EnableScheduling
@EnableAsync
class ServerApplication {

    private val logger = LoggerFactory.getLogger(ServerApplication::class.java)

    @Bean
    fun taskScheduler(): TaskScheduler {
        return ThreadPoolTaskScheduler().apply {
            poolSize = 8
            setThreadNamePrefix("taxi-scheduler-")
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(30)
        }
    }

    @Bean
    fun initDefaultAdmin(
        userRepository: UserRepository,
        passwordEncoder: PasswordEncoder,
        @Value("\${app.admin.initial-password:}") initialAdminPassword: String
    ): CommandLineRunner {
        return CommandLineRunner {
            val adminUsername = "admin"

            if (userRepository.findByUserLogin(adminUsername).isEmpty) {
                val rawPassword = initialAdminPassword.takeIf { it.isNotBlank() }
                    ?: UUID.randomUUID().toString()

                val admin = User().apply {
                    this.userLogin = adminUsername
                    this.fullName = "Головний Адміністратор"
                    this.passwordHash = passwordEncoder.encode(rawPassword)
                    this.role = Role.ADMINISTRATOR
                    this.isBlocked = false
                }
                userRepository.save(admin)
                logger.warn(">>> Створено дефолтного адміністратора '$adminUsername'. Пароль (збережіть та змініть!): $rawPassword")
            }
        }
    }
}

fun main(args: Array<String>) {
    runApplication<ServerApplication>(*args)
}