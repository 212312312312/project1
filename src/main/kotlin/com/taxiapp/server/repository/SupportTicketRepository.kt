package com.taxiapp.server.repository

import com.taxiapp.server.model.support.SupportTicket
import com.taxiapp.server.model.support.TicketStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant // 👈 ДОБАВИТЬ ЭТУ СТРОКУ
import java.util.Optional
import java.util.UUID

@Repository
interface SupportTicketRepository : JpaRepository<SupportTicket, UUID> {
    fun findFirstByTelegramChatIdAndStatusNotOrderByUpdatedAtDesc(
        telegramChatId: Long, 
        status: TicketStatus
    ): Optional<SupportTicket>

    fun findFirstByTelegramChatIdOrderByCreatedAtDesc(telegramChatId: Long): Optional<SupportTicket>

    fun findAllByOrderByUpdatedAtDesc(): List<SupportTicket>
    
    fun findAllByStatusOrderByUpdatedAtDesc(status: TicketStatus): List<SupportTicket>

    fun findAllByStatusAndUpdatedAtBefore(status: TicketStatus, cutoff: Instant): List<SupportTicket>
}