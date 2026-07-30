package com.taxiapp.server.repository

import com.taxiapp.server.model.chat.ChatMessage
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface ChatMessageRepository : JpaRepository<ChatMessage, Long> {
    fun findByOrderIdOrderByCreatedAtAsc(orderId: Long): List<ChatMessage>

    // НОВЫЙ МЕТОД:
    @Modifying
    @Query("DELETE FROM ChatMessage c WHERE c.order.id = :orderId")
    fun deleteAllByOrderId(orderId: Long)
}