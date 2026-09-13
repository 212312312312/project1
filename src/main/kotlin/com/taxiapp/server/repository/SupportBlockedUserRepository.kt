package com.taxiapp.server.repository

import com.taxiapp.server.model.support.SupportBlockedUser
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface SupportBlockedUserRepository : JpaRepository<SupportBlockedUser, UUID> {
    fun existsByTelegramChatId(telegramChatId: Long): Boolean
    fun findAllByOrderByBlockedAtDesc(): List<SupportBlockedUser>
}