package com.taxiapp.server.repository

import com.taxiapp.server.model.enums.Role
import com.taxiapp.server.model.user.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
interface UserRepository : JpaRepository<User, Long> {
    fun findByUserPhone(userPhone: String): Optional<User>
    fun existsByUserPhone(userPhone: String): Boolean
    fun findByUserLogin(userLogin: String): Optional<User>

    fun findAllByRole(role: Role): List<User>
    fun findByEmail(email: String): User?
    fun existsByEmail(email: String): Boolean

    // HIGH-LOAD: Выборка только FCM-токенов без загрузки 100k+ тяжелых полиморфных JPA-сущностей User/Client/Driver в память
    @Query("SELECT u.fcmToken FROM User u WHERE u.fcmToken IS NOT NULL AND u.fcmToken <> '' AND u.isBlocked = false")
    fun findAllActiveFcmTokens(): List<String>

    @Query("SELECT u.fcmToken FROM User u WHERE u.role = :role AND u.fcmToken IS NOT NULL AND u.fcmToken <> '' AND u.isBlocked = false")
    fun findAllActiveFcmTokensByRole(@Param("role") role: Role): List<String>
}