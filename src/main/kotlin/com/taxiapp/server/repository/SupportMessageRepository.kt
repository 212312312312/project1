package com.taxiapp.server.repository

import com.taxiapp.server.model.support.SupportMessage
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface SupportMessageRepository : JpaRepository<SupportMessage, UUID> {
    fun findAllByTicketIdOrderByCreatedAtAsc(ticketId: UUID): List<SupportMessage>
}