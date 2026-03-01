package com.taxiapp.server.service

import com.taxiapp.server.dto.chat.ChatMessageDto
import com.taxiapp.server.model.chat.ChatMessage
import com.taxiapp.server.repository.ChatMessageRepository
import com.taxiapp.server.repository.TaxiOrderRepository
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ChatService(
    private val chatMessageRepository: ChatMessageRepository,
    private val taxiOrderRepository: TaxiOrderRepository,
    private val simpMessagingTemplate: SimpMessagingTemplate,
    private val notificationService: NotificationService
) {

    fun getOrderMessages(orderId: Long): List<ChatMessageDto> {
        return chatMessageRepository.findByOrderIdOrderByCreatedAtAsc(orderId).map {
            ChatMessageDto(it.id, it.order.id!!, it.senderRole, it.senderId, it.content, it.createdAt)
        }
    }
    
    @Transactional
    fun clearChatForOrder(orderId: Long) {
        chatMessageRepository.deleteAllByOrderId(orderId)
    }

    @Transactional
    fun sendMessage(orderId: Long, senderRole: String, content: String): ChatMessageDto {
        val order = taxiOrderRepository.findById(orderId)
            .orElseThrow { RuntimeException("Замовлення не знайдено") }

        // Определяем ID отправителя прямо из заказа для безопасности
        val senderId = if (senderRole == "CLIENT") order.client.id!! else (order.driver?.id ?: 0L)

        val message = ChatMessage(
            order = order,
            senderRole = senderRole,
            senderId = senderId,
            content = content
        )
        
        chatMessageRepository.save(message)

        val dto = ChatMessageDto(
            id = message.id,
            orderId = order.id!!,
            senderRole = message.senderRole,
            senderId = message.senderId,
            content = message.content,
            createdAt = message.createdAt
        )

        // 1. Рассылаем по WebSockets (Оба приложения, если открыты, мгновенно получат сообщение)
        simpMessagingTemplate.convertAndSend("/topic/chat/$orderId", dto)

        // 2. Отправляем Push-уведомление (если приложение свернуто)
        if (senderRole == "CLIENT") {
            order.driver?.let {
                notificationService.sendChatNotification(it.fcmToken, "Нове повідомлення", content, orderId)
            }
        } else {
            notificationService.sendChatNotification(order.client.fcmToken, "Водій написав вам", content, orderId)
        }

        return dto
    }
}