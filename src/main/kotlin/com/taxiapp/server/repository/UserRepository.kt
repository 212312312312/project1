package com.taxiapp.server.repository

import com.taxiapp.server.model.enums.Role
import com.taxiapp.server.model.user.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
interface UserRepository : JpaRepository<User, Long> {
    fun findByUserPhone(userPhone: String): Optional<User>
    fun existsByUserPhone(userPhone: String): Boolean
    fun findByUserLogin(userLogin: String): Optional<User>

    fun findAllByRole(role: Role): List<User>
    fun findByEmail(email: String): User?
    
    // 👇 ДОДАЙ ЦЕЙ РЯДОК:
    fun existsByEmail(email: String): Boolean
}