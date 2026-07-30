package com.taxiapp.server.repository

import com.taxiapp.server.model.auth.RefreshToken
import com.taxiapp.server.model.user.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.Optional

@Repository
interface RefreshTokenRepository : JpaRepository<RefreshToken, Long> {
    fun findByToken(token: String): Optional<RefreshToken>
    
    // --- ДОБАВЛЕНЫ ДВЕ АННОТАЦИИ ---
    @Modifying
    @Transactional
    fun deleteByUser(user: User): Int
}