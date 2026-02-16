package com.taxiapp.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

@Service
class LiqPayService(
    @Value("\${liqpay.public-key}") private val publicKey: String,
    @Value("\${liqpay.private-key}") private val privateKey: String,
    // Добавляем наш URL (Ngrok или боевой домен)
    @Value("\${app.server.url}") private val serverUrl: String
) {

    private val objectMapper = ObjectMapper()
    private val restTemplate = RestTemplate()

    // Генерация ссылки на оплату
    fun generateCheckoutUrl(orderId: String, amount: Double, description: String): String {
        val params = mapOf(
            "action" to "pay",
            "amount" to amount,
            "currency" to "UAH",
            "description" to description,
            "order_id" to orderId,
            "version" to "3",
            "public_key" to publicKey,
            "language" to "uk",
            // ВАЖНО: Указываем LiqPay, куда стучать с результатом
            "server_url" to "$serverUrl/api/v1/payments/callback"
        )

        val json = objectMapper.writeValueAsString(params)
        val data = Base64.getEncoder().encodeToString(json.toByteArray(StandardCharsets.UTF_8))
        val signature = createSignature(data)

        return "https://www.liqpay.ua/api/3/checkout?data=$data&signature=$signature"
    }

    // Проверка статуса платежа (Server-to-Server запрос)
    fun getStatus(orderId: String): String {
        val params = mapOf(
            "action" to "status",
            "version" to "3",
            "public_key" to publicKey,
            "order_id" to orderId
        )

        val json = objectMapper.writeValueAsString(params)
        val data = Base64.getEncoder().encodeToString(json.toByteArray(StandardCharsets.UTF_8))
        val signature = createSignature(data)

        val url = "https://www.liqpay.ua/api/request"
        val requestBody = "data=$data&signature=$signature"

        try {
            val responseEntity = restTemplate.postForEntity(url, requestBody, String::class.java)
            val responseBody = responseEntity.body

            if (responseBody != null) {
                val responseMap = objectMapper.readValue(responseBody, Map::class.java)
                return responseMap["status"]?.toString() ?: "error"
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "error"
    }

    // Публичный метод проверки подписи для Контроллера
    fun isValidSignature(data: String, signature: String): Boolean {
        return createSignature(data) == signature
    }

    private fun createSignature(data: String): String {
        val signString = privateKey + data + privateKey
        val sha1 = MessageDigest.getInstance("SHA-1")
        val hash = sha1.digest(signString.toByteArray(StandardCharsets.UTF_8))
        return Base64.getEncoder().encodeToString(hash)
    }
}