package com.taxiapp.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.taxiapp.server.model.enums.TransactionType
import com.taxiapp.server.model.finance.WalletTransaction
import com.taxiapp.server.repository.DriverRepository
import com.taxiapp.server.repository.WalletTransactionRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.client.RestTemplate
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

@Service
class LiqPayService(
    @Value("\${liqpay.public-key}") private val publicKey: String,
    @Value("\${liqpay.private-key}") private val privateKey: String,
    @Value("\${app.server.url}") private val serverUrl: String,
    
    // Внедряем репозитории для обработки платежа
    private val driverRepository: DriverRepository,
    private val walletTransactionRepository: WalletTransactionRepository
) {

    private val logger = LoggerFactory.getLogger(LiqPayService::class.java)
    private val objectMapper = ObjectMapper()
    private val restTemplate = RestTemplate()

    // Генерация ссылки на оплату
    fun generateCheckoutUrl(orderId: String, amount: Double, description: String): String {
        // ЛОГ: Проверяем, куда LiqPay должен стучать
        val callbackUrl = "$serverUrl/api/v1/payments/callback"
        logger.info(">>> GENERATING LIQPAY LINK. Callback URL: $callbackUrl")

        val params = mapOf(
            "action" to "pay",
            "amount" to amount,
            "currency" to "UAH",
            "description" to description,
            "order_id" to orderId,
            "version" to "3",
            "public_key" to publicKey,
            "language" to "uk",
            "server_url" to callbackUrl,
            "result_url" to "$serverUrl/payment-success.html"
        )

        val json = objectMapper.writeValueAsString(params)
        val data = Base64.getEncoder().encodeToString(json.toByteArray(StandardCharsets.UTF_8))
        val signature = createSignature(data)

        return "https://www.liqpay.ua/api/3/checkout?data=$data&signature=$signature"
    }

    // --- Обработка Callback от LiqPay ---
    @Transactional
    fun processCallback(data: String, signature: String): String {
        // 1. Проверяем подпись
        if (!isValidSignature(data, signature)) {
            logger.error(">>> LiqPay Callback ERROR: Invalid signature")
            return "Invalid signature"
        }

        // 2. Декодируем данные
        val json = String(Base64.getDecoder().decode(data), StandardCharsets.UTF_8)
        val params = objectMapper.readValue(json, Map::class.java)

        val status = params["status"] as? String
        val orderId = params["order_id"] as? String
        val amount = (params["amount"] as? Number)?.toDouble() ?: 0.0
        
        logger.info(">>> LiqPay Callback RECEIVED: orderId=$orderId, status=$status, amount=$amount")

        // 3. Проверяем статус (success или sandbox)
        if (status == "success" || status == "sandbox") {
            if (orderId == null) {
                logger.error(">>> LiqPay Error: order_id is null")
                return "No order_id"
            }

            // --- ИСПРАВЛЕНИЕ ОШИБКИ КОМПИЛЯЦИИ ЗДЕСЬ ---
            // Используем безопасный вызов ?. и сравнение с true
            val existing = walletTransactionRepository.findAll().find { 
                it.description?.contains(orderId) == true 
            }
            
            if (existing != null) {
                logger.info(">>> Transaction $orderId already processed. Skipping.")
                return "Already processed"
            }

            // 4. Парсим ID водителя
            try {
                // Ожидаемый формат: topup_driver_{id}_{timestamp}
                val parts = orderId.split("_")
                
                if (parts.size >= 3 && parts[1] == "driver") {
                    val driverId = parts[2].toLong()
                    val driver = driverRepository.findById(driverId).orElse(null)

                    if (driver != null) {
                        // 5. Пополняем баланс
                        val oldBalance = driver.balance
                        driver.balance += amount
                        driverRepository.save(driver)

                        // 6. Создаем транзакцию
                        val transaction = WalletTransaction(
                            driver = driver,
                            amount = amount,
                            operationType = TransactionType.DEPOSIT,
                            description = "Поповнення через LiqPay (ID: $orderId)"
                        )
                        walletTransactionRepository.save(transaction)
                        
                        logger.info(">>> BALANCE UPDATED SUCCESS! Driver: ${driver.id}, Old: $oldBalance, New: ${driver.balance}, Added: $amount")
                    } else {
                        logger.error(">>> LiqPay Error: Driver with ID $driverId not found")
                    }
                } else {
                    logger.error(">>> LiqPay Error: Invalid order_id format: $orderId")
                }
            } catch (e: Exception) {
                logger.error(">>> Error processing LiqPay callback parsing", e)
            }
        } else {
            logger.warn(">>> LiqPay Payment Status is NOT success: $status")
        }

        return "OK"
    }

    // Проверка статуса (Server-to-Server)
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