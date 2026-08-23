package com.taxiapp.server.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate

@Service
class TelegramBotService(
    @Value("\${telegram.bot.token}") private val botToken: String
) {
    private val log = LoggerFactory.getLogger(TelegramBotService::class.java)
    private val restTemplate = RestTemplate()
    private val apiUrl: String get() = "https://api.telegram.org/bot$botToken"

    fun sendMessage(chatId: Long, text: String) {
        val payload = mapOf(
            "chat_id" to chatId,
            "text" to text
        )
        postToTelegram("/sendMessage", payload)
    }

    fun sendRequestContactButton(chatId: Long, text: String) {
        val keyboard = mapOf(
            "keyboard" to listOf(
                listOf(
                    mapOf(
                        "text" to "📱 Поділитися номером телефону",
                        "request_contact" to true
                    )
                )
            ),
            "resize_keyboard" to true,
            "one_time_keyboard" to true
        )

        val payload = mapOf(
            "chat_id" to chatId,
            "text" to text,
            "reply_markup" to keyboard
        )
        postToTelegram("/sendMessage", payload)
    }

    fun sendTicketClosedNotification(chatId: Long) {
        val removeKeyboard = mapOf("remove_keyboard" to true)
        val payload = mapOf(
            "chat_id" to chatId,
            "text" to "🔒 Підтримка закрила ваш тікет.\nЯкщо у вас виникнуть нові питання, просто напишіть у цей чат!",
            "reply_markup" to removeKeyboard
        )
        postToTelegram("/sendMessage", payload)
    }

    private fun postToTelegram(method: String, body: Any) {
        try {
            val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
            val entity = HttpEntity(body, headers)
            restTemplate.postForObject("$apiUrl$method", entity, String::class.java)
        } catch (e: Exception) {
            log.error("Помилка відправки в Telegram [$method]: ${e.message}", e)
        }
    }
}