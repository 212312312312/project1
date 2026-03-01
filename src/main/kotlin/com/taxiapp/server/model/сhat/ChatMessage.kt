package com.taxiapp.server.model.chat

import com.taxiapp.server.model.order.TaxiOrder
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import java.time.LocalDateTime

@Entity
@Table(name = "chat_messages")
class ChatMessage(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    var order: TaxiOrder,

    @Column(nullable = false)
    var senderRole: String, // "CLIENT" или "DRIVER"

    @Column(nullable = false)
    var senderId: Long,

    @Column(nullable = false, length = 1000)
    var content: String,

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()
)