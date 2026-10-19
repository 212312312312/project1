package com.taxiapp.server.service

import com.taxiapp.server.dto.chat.ChatMessageDto
import com.taxiapp.server.model.chat.ChatMessage
import com.taxiapp.server.model.enums.Role
import com.taxiapp.server.model.order.TaxiOrder
import com.taxiapp.server.model.user.User
import com.taxiapp.server.repository.ChatMessageRepository
import com.taxiapp.server.repository.TaxiOrderRepository
import org.springframework.http.HttpStatus
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
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
            taxiOrderRepository.findById(idOrUuid.toLong())
                .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено за ID: $idOrUuid") }
        } else {
            val parsedUuid = try {
                UUID.fromString(idOrUuid)
            } catch (e: Exception) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Невірний формат ідентифікатора замовлення")
            }
            taxiOrderRepository.findByUuid(parsedUuid)
                .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено за UUID: $idOrUuid") }
        }
    }

    @Transactional(readOnly = true)
    fun getOrderMessages(user: User, orderIdOrUuid: String): List<ChatMessageDto> {
        val order = findOrderByIdOrUuid(orderIdOrUuid)
        val isParticipant = order.client.id == user.id || order.driver?.id == user.id
        val isAdminOrDispatcher = user.role == Role.ADMINISTRATOR || user.role == Role.DISPATCHER

        if (!isParticipant && !isAdminOrDispatcher) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Ви не є учасником цього замовлення")
        }

        return chatMessageRepository.findByOrderIdOrderByCreatedAtAsc(order.id!!).map {
            ChatMessageDto(it.id, it.order.uuid.toString(), it.senderRole, it.senderId, it.content, it.createdAt)
        }
    }

    @Transactional
    fun clearChatForOrder(orderId: Long) {
        chatMessageRepository.deleteAllByOrderId(orderId)
    }

    @Transactional
    fun sendMessage(user: User, orderIdOrUuid: String, senderRole: String, content: String): ChatMessageDto {
        val cleanContent = content.trim()
        if (cleanContent.isEmpty() || cleanContent.length > 1000) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Повідомлення повинно містити від 1 до 1000 символів")
        }

        val order = findOrderByIdOrUuid(orderIdOrUuid)
        val internalId = order.id!!

        val senderId = when (senderRole) {
            "CLIENT" -> {
                if (order.client.id != user.id) {
                    throw ResponseStatusException(HttpStatus.FORBIDDEN, "Це не ваше замовлення")
                }
                order.client.id!!
            }
            "DRIVER" -> {
                val assignedDriverId = order.driver?.id
                    ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "На замовлення ще не призначено водія")
                if (assignedDriverId != user.id) {
                    throw ResponseStatusException(HttpStatus.FORBIDDEN, "Ви не є водієм цього замовлення")
                }
                assignedDriverId
            }
            else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Невідома роль відправника")
        }

        val message = ChatMessage(
            order = order,
            senderRole = senderRole,
            senderId = senderId,
            content = cleanContent
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

        // 1. Рассылаем по WebSockets
        simpMessagingTemplate.convertAndSend("/topic/chat/$internalId", dto)

        // 2. Отправляем Push-уведомление
        if (senderRole == "CLIENT") {
            order.driver?.let {
                notificationService.sendChatNotification(it.fcmToken, "Нове повідомлення", cleanContent, internalId)
            }
        } else {
            notificationService.sendChatNotification(order.client.fcmToken, "Водій написав вам", cleanContent, internalId)
        }

        return dto
    }
}