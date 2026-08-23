package com.taxiapp.server.service

import com.taxiapp.server.dto.support.*
import com.taxiapp.server.model.support.MessageSenderType
import com.taxiapp.server.model.support.SupportMessage
import com.taxiapp.server.model.support.SupportTicket
import com.taxiapp.server.model.support.TicketStatus
import com.taxiapp.server.repository.SupportMessageRepository
import com.taxiapp.server.repository.SupportTicketRepository
import com.taxiapp.server.repository.UserRepository
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class SupportService(
    private val ticketRepository: SupportTicketRepository,
    private val messageRepository: SupportMessageRepository,
    private val userRepository: UserRepository,
    private val telegramBotService: TelegramBotService,
    private val messagingTemplate: SimpMessagingTemplate,
    private val antiSpamService: SupportAntiSpamService // 👈 Добавить инжекцию
){

    @Transactional
    fun handleTelegramUpdate(update: TelegramUpdate) {
        val message = update.message ?: return
        val chatId = message.chat.id

        if (antiSpamService.isSpam(chatId, message.text)) {
        return
        }

        // 1. Користувач надіслав свій контакт (номер телефону)
        if (message.contact != null) {
            val rawPhone = message.contact.phoneNumber
            val normalizedPhone = if (rawPhone.startsWith("+")) rawPhone else "+$rawPhone"
            val user = userRepository.findByUserPhone(normalizedPhone).orElse(null)

            val existingTicket = ticketRepository.findFirstByTelegramChatIdAndStatusNotOrderByUpdatedAtDesc(
                chatId, TicketStatus.CLOSED
            ).orElse(null)

            if (existingTicket != null) {
                existingTicket.phoneNumber = normalizedPhone
                existingTicket.user = user
                existingTicket.updatedAt = Instant.now()
                ticketRepository.save(existingTicket)
            } else {
                ticketRepository.save(
                    SupportTicket(
                        telegramChatId = chatId,
                        phoneNumber = normalizedPhone,
                        status = TicketStatus.OPEN,
                        user = user
                    )
                )
            }

            telegramBotService.sendMessage(
                chatId,
                "✅ Дякуємо! Ваш номер підтверджено. Опишіть, будь ласка, ваше питання або проблему, і диспетчер одразу підключиться."
            )
            notifyTicketsChanged()
            return
        }

        // 2. Команда /start
        if (message.text == "/start") {
            telegramBotService.sendRequestContactButton(
                chatId,
                "👋 Вітаємо у службі підтримки!\nБудь ласка, поділіться номером телефону для ідентифікації вашого профілю:"
            )
            return
        }

        // 3. Текстове повідомлення
        val text = message.text?.trim() ?: return
        val activeTicketOpt = ticketRepository.findFirstByTelegramChatIdAndStatusNotOrderByUpdatedAtDesc(
            chatId, TicketStatus.CLOSED
        )

        val ticket = if (activeTicketOpt.isPresent) {
            activeTicketOpt.get()
        } else {
            val lastTicket = ticketRepository.findFirstByTelegramChatIdOrderByCreatedAtDesc(chatId).orElse(null)
            val phone = lastTicket?.phoneNumber ?: "Невідомий"
            val user = lastTicket?.user

            ticketRepository.save(
                SupportTicket(
                    telegramChatId = chatId,
                    phoneNumber = phone,
                    status = TicketStatus.OPEN,
                    user = user
                )
            )
        }

        ticket.updatedAt = Instant.now()
        ticketRepository.save(ticket)

        val savedMsg = messageRepository.save(
            SupportMessage(
                ticket = ticket,
                senderType = MessageSenderType.CLIENT,
                text = text,
                telegramMessageId = message.messageId
            )
        )

        // Відправляємо по WebSocket у диспетчерську
        messagingTemplate.convertAndSend("/topic/support/messages/${ticket.id}", mapToMessageDto(savedMsg))
        notifyTicketsChanged()
    }

    @Transactional(readOnly = true)
    fun getAllTickets(status: TicketStatus?): List<SupportTicketDto> {
        val tickets = if (status != null) {
            ticketRepository.findAllByStatusOrderByUpdatedAtDesc(status)
        } else {
            ticketRepository.findAllByOrderByUpdatedAtDesc()
        }

        return tickets.map { ticket ->
            val messages = messageRepository.findAllByTicketIdOrderByCreatedAtAsc(ticket.id!!)
            val lastMsg = messages.lastOrNull()?.text
            SupportTicketDto(
                id = ticket.id,
                telegramChatId = ticket.telegramChatId,
                phoneNumber = ticket.phoneNumber,
                userName = ticket.user?.fullName ?: "Гість",
                userRole = ticket.user?.role?.name ?: "GUEST",
                status = ticket.status,
                lastMessage = lastMsg,
                createdAt = ticket.createdAt,
                updatedAt = ticket.updatedAt
            )
        }
    }

    @Transactional(readOnly = true)
    fun getTicketMessages(ticketId: UUID): List<SupportMessageDto> {
        return messageRepository.findAllByTicketIdOrderByCreatedAtAsc(ticketId).map { mapToMessageDto(it) }
    }

    @Transactional
    fun replyFromDispatcher(ticketId: UUID, text: String): SupportMessageDto {
        val ticket = ticketRepository.findById(ticketId)
            .orElseThrow { IllegalArgumentException("Тікет не знайдено") }

        if (ticket.status == TicketStatus.OPEN) {
            ticket.status = TicketStatus.IN_PROGRESS
        }
        ticket.updatedAt = Instant.now()
        ticketRepository.save(ticket)

        val savedMsg = messageRepository.save(
            SupportMessage(
                ticket = ticket,
                senderType = MessageSenderType.DISPATCHER,
                text = text
            )
        )

        telegramBotService.sendMessage(ticket.telegramChatId, text)

        val dto = mapToMessageDto(savedMsg)
        messagingTemplate.convertAndSend("/topic/support/messages/${ticket.id}", dto)
        notifyTicketsChanged()
        return dto
    }

    @Transactional
    fun closeTicket(ticketId: UUID) {
        val ticket = ticketRepository.findById(ticketId)
            .orElseThrow { IllegalArgumentException("Тікет не знайдено") }

        ticket.status = TicketStatus.CLOSED
        ticket.updatedAt = Instant.now()
        ticketRepository.save(ticket)

        telegramBotService.sendTicketClosedNotification(ticket.telegramChatId)
        notifyTicketsChanged()
    }

    @Transactional
fun startChat(ticketId: UUID): SupportMessageDto {
    val ticket = ticketRepository.findById(ticketId)
        .orElseThrow { IllegalArgumentException("Тікет не знайдено") }

    ticket.status = TicketStatus.IN_PROGRESS
    ticket.updatedAt = Instant.now()
    ticketRepository.save(ticket)

    val autoReplyText = "Підтримка підключилась до чату.\nВітаємо! Ознайомлюємося з вашим питанням і вже працюємо над його вирішенням."

    val savedMsg = messageRepository.save(
        SupportMessage(
            ticket = ticket,
            senderType = MessageSenderType.DISPATCHER,
            text = autoReplyText
        )
    )

    telegramBotService.sendMessage(ticket.telegramChatId, autoReplyText)

    val dto = mapToMessageDto(savedMsg)
    messagingTemplate.convertAndSend("/topic/support/messages/${ticket.id}", dto)
    notifyTicketsChanged()
    return dto
}


// Додати в клас SupportService:
@Transactional
fun deleteOldClosedTickets() {
    val threeDaysAgo = Instant.now().minus(3, java.time.temporal.ChronoUnit.DAYS)
    val oldTickets = ticketRepository.findAllByStatusAndUpdatedAtBefore(TicketStatus.CLOSED, threeDaysAgo)
    if (oldTickets.isNotEmpty()) {
        ticketRepository.deleteAll(oldTickets)
    }
}
    private fun notifyTicketsChanged() {
        messagingTemplate.convertAndSend("/topic/support/tickets", "REFRESH")
    }

    private fun mapToMessageDto(msg: SupportMessage) = SupportMessageDto(
        id = msg.id!!,
        ticketId = msg.ticket.id!!,
        senderType = msg.senderType,
        text = msg.text,
        createdAt = msg.createdAt
    )
}