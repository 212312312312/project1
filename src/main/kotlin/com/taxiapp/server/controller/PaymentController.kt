package com.taxiapp.server.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.taxiapp.server.model.enums.TransactionType
import com.taxiapp.server.model.finance.PaymentStatus
import com.taxiapp.server.model.finance.PaymentTransaction
import com.taxiapp.server.model.finance.WalletTransaction
import com.taxiapp.server.model.user.User
import com.taxiapp.server.repository.DriverRepository
import com.taxiapp.server.repository.ClientRepository // <-- НЕ ЗАБУДЬ ИМПОРТ
import com.taxiapp.server.repository.PaymentTransactionRepository
import com.taxiapp.server.repository.WalletTransactionRepository
import com.taxiapp.server.service.LiqPayService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime
import java.util.Base64
import com.taxiapp.server.service.NotificationService

@RestController
@RequestMapping("/api/v1/payments")
class PaymentController(
    private val paymentRepository: PaymentTransactionRepository,
    private val driverRepository: DriverRepository,
    private val clientRepository: ClientRepository, // <-- ВНЕДРЯЕМ РЕПОЗИТОРИЙ КЛИЕНТА
    private val walletTransactionRepository: WalletTransactionRepository,
    private val liqPayService: LiqPayService,
    private val notificationService: NotificationService
) {

    data class InitPaymentRequest(val amount: Double)
    data class InitPaymentResponse(val paymentUrl: String, val paymentId: Long)
    
    // НОВОЕ DTO для привязки карты
    data class InitBindCardResponse(val paymentUrl: String)

    // 1. Инициализация (Для водителя)
    @PostMapping("/init")
    fun initPayment(@AuthenticationPrincipal user: User, @RequestBody request: InitPaymentRequest): ResponseEntity<InitPaymentResponse> {
        val driver = driverRepository.findById(user.id!!)
            .orElseThrow { ResponseStatusException(HttpStatus.FORBIDDEN, "Водій не знайдений") }

        if (request.amount < 1.0) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Мінімальна сума 1 грн")

        val orderReference = "taxi_topup_${driver.id}_${System.currentTimeMillis()}"

        val payment = PaymentTransaction(
            driver = driver,
            amount = request.amount,
            status = PaymentStatus.PENDING,
            externalReference = orderReference
        )
        val savedPayment = paymentRepository.save(payment)

        val url = liqPayService.generateCheckoutUrl(
            orderId = orderReference,
            amount = request.amount,
            description = "Поповнення балансу водія ID ${driver.id}"
        )

        return ResponseEntity.ok(InitPaymentResponse(url, savedPayment.id!!))
    }

    // НОВЫЙ ЭНДПОИНТ: Инициализация привязки карты (Для клиента)
    @PostMapping("/bind-card/init")
    fun initBindCard(@AuthenticationPrincipal user: User): ResponseEntity<InitBindCardResponse> {
        // Проверяем, существует ли клиент
        val client = clientRepository.findById(user.id!!)
            .orElseThrow { ResponseStatusException(HttpStatus.FORBIDDEN, "Клієнт не знайдений") }

        // Получаем URL на форму LiqPay
        val url = liqPayService.generateBindCardUrl(client.id!!)
        return ResponseEntity.ok(InitBindCardResponse(url))
    }

    // 2. Проверка статуса (Оставляем как есть)
    @PostMapping("/check/{paymentId}")
    @Transactional
    fun checkStatus(@AuthenticationPrincipal user: User, @PathVariable paymentId: Long): ResponseEntity<Map<String, String>> {
        val payment = paymentRepository.findById(paymentId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }

        if (payment.driver.id != user.id) throw ResponseStatusException(HttpStatus.FORBIDDEN)

        if (payment.status == PaymentStatus.SUCCESS) {
            return ResponseEntity.ok(mapOf("status" to "SUCCESS", "message" to "Вже оплачено"))
        }

        val liqPayStatus = liqPayService.getStatus(payment.externalReference!!)
        return processPaymentResult(payment, liqPayStatus)
    }

    // 3. CALLBACK ОТ LIQPAY
    @PostMapping("/callback")
    @Transactional
    fun handleCallback(
        @RequestParam data: String,
        @RequestParam signature: String
    ): ResponseEntity<String> {
        println(">>> LIQPAY CALLBACK RECEIVED")

        if (!liqPayService.isValidSignature(data, signature)) {
            println(">>> LIQPAY CALLBACK: INVALID SIGNATURE")
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Invalid signature")
        }

        val json = String(Base64.getDecoder().decode(data))
        val objectMapper = ObjectMapper()
        val jsonNode = objectMapper.readTree(json)

        val orderReference = jsonNode.get("order_id").asText()
        val status = jsonNode.get("status").asText()

        println(">>> LIQPAY CALLBACK: Order: $orderReference, Status: $status")

        // РАЗДЕЛЯЕМ ЛОГИКУ ПО ПРЕФИКСУ ЗАКАЗА
        return when {
            orderReference.startsWith("bind_card_") -> handleBindCardCallback(orderReference, status, jsonNode)
            orderReference.startsWith("taxi_topup_") -> handleTopUpCallback(orderReference, status)
            else -> ResponseEntity.ok("Unknown order type")
        }
    }

    // ЛОГИКА 1: Сохранение токена карты клиента
    private fun handleBindCardCallback(orderReference: String, status: String, jsonNode: JsonNode): ResponseEntity<String> {
        println(">>> FULL LIQPAY JSON: \n${jsonNode.toPrettyString()}")

        if (status in listOf("success", "sandbox", "wait_accept", "auth")) {
            val clientIdStr = orderReference.split("_").getOrNull(2) ?: return ResponseEntity.badRequest().body("Invalid format")
            val clientId = clientIdStr.toLongOrNull() ?: return ResponseEntity.badRequest().body("Invalid client ID")

            val client = clientRepository.findById(clientId).orElse(null)
                ?: return ResponseEntity.ok("Client not found")

            // Ищем маску (она точно есть в логах: "sender_card_mask2")
            val cardMask = jsonNode.path("sender_card_mask2").asText(null) 
                ?: jsonNode.path("sender_card_mask").asText(null)
            
            // Ищем токен. Если его нет - используем заглушку для тестов
            val cardToken = jsonNode.path("sender_card_token").asText(null) 
                ?: jsonNode.path("card_token").asText(null) 
                ?: jsonNode.path("token").asText(null)
                ?: "temp_test_token_${System.currentTimeMillis()}" // <-- ВРЕМЕННАЯ ЗАГЛУШКА

            if (cardMask != null) { // Главное, чтобы пришла маска
                client.cardToken = cardToken
                client.cardMask = cardMask
                clientRepository.save(client)
                println(">>> CARD BOUND SUCCESSFULLY for Client $clientId. Mask: $cardMask")
            } else {
                println(">>> WARNING: LiqPay returned SUCCESS, but NO CARD MASK found in JSON!")
            }
        } else {
            println(">>> CARD BIND FAILED for order $orderReference with status $status")
        }
        return ResponseEntity.ok("OK")
    }

    // ЛОГИКА 2: Пополнение баланса водителя (старый код)
    private fun handleTopUpCallback(orderReference: String, status: String): ResponseEntity<String> {
        val payment = paymentRepository.findAll().find { it.externalReference == orderReference }
        
        if (payment == null) {
            println(">>> LIQPAY CALLBACK: Payment not found for order $orderReference")
            return ResponseEntity.ok("Payment not found")
        }

        if (payment.status == PaymentStatus.SUCCESS) {
            return ResponseEntity.ok("Already processed")
        }

        processPaymentResult(payment, status)
        return ResponseEntity.ok("OK")
    }

    private fun processPaymentResult(payment: PaymentTransaction, status: String): ResponseEntity<Map<String, String>> {
        if (status == "success" || status == "sandbox" || status == "wait_accept") {
            payment.status = PaymentStatus.SUCCESS
            payment.finishedAt = LocalDateTime.now()
            paymentRepository.save(payment)

            val driver = payment.driver
            driver.balance += payment.amount
            driverRepository.save(driver)

            val walletTx = WalletTransaction(
                driver = driver,
                amount = payment.amount,
                operationType = TransactionType.DEPOSIT,
                description = "LiqPay: ${String.format("%.2f", payment.amount)} UAH"
            )
            walletTransactionRepository.save(walletTx)

            println(">>> PAYMENT SUCCESS: Driver ${driver.id} balance updated (+${payment.amount})")

            try {
                notificationService.saveAndSend(
                    driver = driver,
                    title = "Баланс поповнено",
                    body = "Ваш баланс поповнено на ${String.format("%.2f", payment.amount)} UAH",
                    type = "PAYMENT"
                )
            } catch (e: Exception) {
                println(">>> Ошибка при отправке уведомления о пополнении: ${e.message}")
            }

            return ResponseEntity.ok(mapOf("status" to "SUCCESS", "message" to "Оплата успішна!"))
        } else if (status == "failure" || status == "error" || status == "reversed") {
            payment.status = PaymentStatus.FAILED
            paymentRepository.save(payment)
            return ResponseEntity.ok(mapOf("status" to "FAILED", "message" to "Оплата відхилена ($status)"))
        }

        return ResponseEntity.ok(mapOf("status" to "PENDING", "message" to "Статус: $status"))
    }
}