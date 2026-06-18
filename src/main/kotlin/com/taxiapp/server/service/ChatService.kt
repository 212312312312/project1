package com.taxiapp.server.service

import com.taxiapp.server.dto.chat.ChatMessageDto
import com.taxiapp.server.model.chat.ChatMessage
import com.taxiapp.server.model.order.TaxiOrder
import com.taxiapp.server.repository.ChatMessageRepository
import com.taxiapp.server.repository.TaxiOrderRepository
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class ChatService(
    private val chatMessageRepository: ChatMessageRepository,
    private val taxiOrderRepository: TaxiOrderRepository,
    private val simpMessagingTemplate: SimpMessagingTemplate,
    private val notificationService: NotificationService
) {

    // Вспомогательный метод для определения типа ID (число или UUID)
    private fun findOrderByIdOrUuid(idOrUuid: String): TaxiOrder {
        return if (idOrUuid.toLongOrNull() != null) {
            // Если пришло число (водительское приложение)
            taxiOrderRepository.findById(idOrUuid.toLong())
                .orElseThrow { RuntimeException("Замовлення не знайдено за ID: $idOrUuid") }
        } else {
            // Если пришла строка UUID (клиентское приложение)
            val parsedUuid = try {
                UUID.fromString(idOrUuid)
            } catch (e: Exception) {
                throw RuntimeException("Невірний формат идентификатора замовлення")
            }
            taxiOrderRepository.findByUuid(parsedUuid)
                .orElseThrow { RuntimeException("Замовлення не знайдено за UUID: $idOrUuid") }
        }
    }

    fun getOrderMessages(orderIdOrUuid: String): List<ChatMessageDto> {
        val order = findOrderByIdOrUuid(orderIdOrUuid)
        return chatMessageRepository.findByOrderIdOrderByCreatedAtAsc(order.id!!).map {
            ChatMessageDto(it.id, it.order.uuid.toString(), it.senderRole, it.senderId, it.content, it.createdAt)
        }
    }
    
    @Transactional
    fun clearChatForOrder(orderId: Long) {
        chatMessageRepository.deleteAllByOrderId(orderId)
    }

    @Transactional
    fun sendMessage(orderIdOrUuid: String, senderRole: String, content: String): ChatMessageDto {
        val order = findOrderByIdOrUuid(orderIdOrUuid)
        val internalId = order.id!!

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
            orderId = order.uuid.toString(),
            senderRole = message.senderRole,
            senderId = message.senderId,
            content = message.content,
            createdAt = message.createdAt
        )

        // 1. Рассылаем по WebSockets (в пути сокета используем внутренний id для стабильности подписки)
        simpMessagingTemplate.convertAndSend("/topic/chat/$internalId", dto)

        // 2. Отправляем Push-уведомление (если приложение свернуто)
        if (senderRole == "CLIENT") {
            order.driver?.let {
                notificationService.sendChatNotification(it.fcmToken, "Нове повідомлення", content, internalId)
            }
        } else {
            notificationService.sendChatNotification(order.client.fcmToken, "Водій написав вам", content, internalId)
        }

        return dto
    }
}