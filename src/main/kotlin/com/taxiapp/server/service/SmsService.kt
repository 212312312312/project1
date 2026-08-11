package com.taxiapp.server.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient

@Service
class SmsService(
    private val settingsService: SettingsService,
    @Value("\${smsfly.api-key:wJNrJZBlStILNpAomo9yroCRZOK303C0}") private val apiKey: String,
    @Value("\${smsfly.api-url:https://sms-fly.ua/api/v2/api.php}") private val apiUrl: String,
    @Value("\${smsfly.alpha-name:TAXI}") private val defaultAlphaName: String
) {

    private val logger = LoggerFactory.getLogger(SmsService::class.java)
    private val restClient = RestClient.create()

    /**
     * Отправка SMS с автоматическим выбором между TEST и PROD режимом
     */
    fun sendSms(phoneNumber: String, message: String) {
        val isProd = settingsService.isSmsProdMode()

        if (!isProd) {
            // === РЕЖИМ ТЕСТИРОВАНИЯ (Вывод в логи) ===
            logger.info("==================================================")
            logger.info("MOCK SMS SERVICE (ТЕСТОВИЙ РЕЖИМ)")
            logger.info("НА НОМЕР: $phoneNumber")
            logger.info("ПОВІДОМЛЕННЯ: $message")
            logger.info("==================================================")
            return
        }

        // === ПРОДАКШЕН РЕЖИМ (SMS-Fly REST API v2.4) ===
        try {
            val formattedPhone = formatPhoneForSmsFly(phoneNumber)
            val alphaName = settingsService.getSmsAlphaName().ifBlank { defaultAlphaName }

            // Формируем JSON запрос строго по документации v2.4
            val requestBody = mapOf(
                "auth" to mapOf(
                    "key" to apiKey
                ),
                "action" to "SENDMESSAGE",
                "data" to mapOf(
                    "recipient" to formattedPhone,
                    "channels" to listOf("sms"),
                    "sms" to mapOf(
                        "source" to alphaName,
                        "ttl" to 5,
                        "text" to message
                    )
                )
            )

            logger.info("Отправка реального SMS на номер (PROD): {}", maskPhoneNumber(formattedPhone))

            val response = restClient.post()
                .uri(apiUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody) // Заголовки авторизации НЕ НУЖНЫ, ключ внутри body!
                .exchange { _, response ->
                    val bodyString = response.body.bufferedReader().use { it.readText() }
                    if (response.statusCode.is2xxSuccessful) {
                        logger.info("SMS-Fly SUCCESS Response: {}", bodyString)
                    } else {
                        logger.error("SMS-Fly ERROR Status: {}, Body: {}", response.statusCode, bodyString)
                    }
                    bodyString
                }

        } catch (e: Exception) {
            logger.error("Ошибка при отправке SMS через SMS-Fly: {}", e.message, e)
        }
    }

    /**
     * Приводит номер к формату SMS-Fly (380XXXXXXXXX без знака '+')
     */
    private fun formatPhoneForSmsFly(phone: String): String {
    val cleaned = phone.replace(Regex("[^0-9]"), "")
    return when {
        cleaned.startsWith("0") -> "38$cleaned" // ✅ "0661821815" -> "380661821815"
        cleaned.startsWith("380") -> cleaned
        cleaned.length == 9 -> "380$cleaned" // "661821815" -> "380661821815"
        else -> cleaned
    }
}

    /**
     * Маскирование номера для безопасного логирования в PROD
     */
    private fun maskPhoneNumber(phone: String): String {
        return if (phone.length >= 10) {
            phone.substring(0, 5) + "****" + phone.substring(phone.length - 2)
        } else {
            "***"
        }
    }
}