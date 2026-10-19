package com.taxiapp.server.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.taxiapp.server.model.enums.TransactionType
import com.taxiapp.server.model.finance.PaymentStatus
import com.taxiapp.server.model.finance.PaymentTransaction
import com.taxiapp.server.model.finance.WalletTransaction
import com.taxiapp.server.model.user.User
import com.taxiapp.server.repository.ClientRepository
import com.taxiapp.server.repository.DriverRepository
import com.taxiapp.server.repository.PaymentTransactionRepository
import com.taxiapp.server.repository.WalletTransactionRepository
import com.taxiapp.server.service.LiqPayService
import com.taxiapp.server.service.NotificationService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime
import java.util.Base64

@RestController
@RequestMapping("/api/v1/payments")
class PaymentController(
    private val paymentRepository: PaymentTransactionRepository,
    private val driverRepository: DriverRepository,
    private val clientRepository: ClientRepository,
    private val walletTransactionRepository: WalletTransactionRepository,
    private val liqPayService: LiqPayService,
    private val notificationService: NotificationService,
    private val driverService: com.taxiapp.server.service.DriverService,
    private val messagingTemplate: SimpMessagingTemplate
) {

    data class InitPaymentRequest(val amount: Double)
    data class InitPaymentResponse(val paymentUrl: String, val paymentId: Long)
    data class InitBindCardResponse(val paymentUrl: String)

    // 1. Инициализация пополнения баланса (Для водителя)
    @PostMapping("/init")
    fun initPayment(@AuthenticationPrincipal user: User, @RequestBody request: InitPaymentRequest): ResponseEntity<InitPaymentResponse> {
        val driver = driverRepository.findById(user.id)
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

    // Инициализация привязки карты (Для клиента)
    @PostMapping("/bind-card/init")
    fun initBindCard(@AuthenticationPrincipal user: User): ResponseEntity<InitBindCardResponse> {
        val client = clientRepository.findById(user.id)
            .orElseThrow { ResponseStatusException(HttpStatus.FORBIDDEN, "Клієнт не знайдений") }

        val url = liqPayService.generateBindCardUrl(client.id)
        return ResponseEntity.ok(InitBindCardResponse(url))
    }

    @DeleteMapping("/unbind-card")
    fun unbindCard(@AuthenticationPrincipal user: User): ResponseEntity<Map<String, String>> {
        val client = clientRepository.findById(user.id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Клієнт не знайдений") }

        client.cardToken = null
        client.cardMask = null
        clientRepository.save(client)

        return ResponseEntity.ok(mapOf("message" to "Картку успішно відв'язано"))
    }

    // 2. Проверка статуса
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
        if (!liqPayService.isValidSignature(data, signature)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Invalid signature")
        }

        val json = String(Base64.getDecoder().decode(data))
        val objectMapper = ObjectMapper()
        val jsonNode = objectMapper.readTree(json)

        val orderReference = jsonNode.get("order_id").asText()
        val status = jsonNode.get("status").asText()

        return when {
            orderReference.startsWith("bind_card_") -> handleBindCardCallback(orderReference, status, jsonNode)
            orderReference.startsWith("bind_driver_card_") -> handleBindDriverCardCallback(orderReference, status, jsonNode)
            orderReference.startsWith("taxi_topup_") -> handleTopUpCallback(orderReference, status)
            else -> ResponseEntity.ok("Unknown order type")
        }
    }

    private fun handleBindDriverCardCallback(orderReference: String, status: String, jsonNode: JsonNode): ResponseEntity<String> {
        val driverIdStr = orderReference.split("_").getOrNull(3) ?: return ResponseEntity.badRequest().body("Invalid format")
        val driverId = driverIdStr.toLongOrNull() ?: return ResponseEntity.badRequest().body("Invalid driver ID")

        if (status in listOf("success", "sandbox", "wait_accept", "auth")) {
            val cardMask = jsonNode.path("sender_card_mask2").asText(null) ?: jsonNode.path("sender_card_mask").asText(null)
            val cardToken = jsonNode.path("sender_card_token").asText(null) ?: jsonNode.path("card_token").asText(null) ?: jsonNode.path("token").asText(null)

            if (!cardMask.isNullOrEmpty()) {
                val finalToken = cardToken ?: "sandbox_driver_token_${jsonNode.path("payment_id").asText()}"
                driverService.completeDriverCardBinding(driverId, finalToken, cardMask)

                messagingTemplate.convertAndSend(
                    "/topic/drivers/$driverId/card-bind",
                    mapOf("status" to "SUCCESS", "cardMask" to cardMask)
                )
            }
        } else {
            messagingTemplate.convertAndSend(
                "/topic/drivers/$driverId/card-bind",
                mapOf("status" to "FAILED", "message" to "Status: $status")
            )
        }
        return ResponseEntity.ok("OK")
    }

    private fun handleBindCardCallback(orderReference: String, status: String, jsonNode: JsonNode): ResponseEntity<String> {
        if (status in listOf("success", "sandbox", "wait_accept", "auth")) {
            val clientIdStr = orderReference.split("_").getOrNull(2) ?: return ResponseEntity.badRequest().body("Invalid format")
            val clientId = clientIdStr.toLongOrNull() ?: return ResponseEntity.badRequest().body("Invalid client ID")

            val client = clientRepository.findById(clientId).orElse(null)
                ?: return ResponseEntity.ok("Client not found")

            val cardMask = jsonNode.path("sender_card_mask2").asText(null)
                ?: jsonNode.path("sender_card_mask").asText(null)

            val cardToken = jsonNode.path("sender_card_token").asText(null)
                ?: jsonNode.path("card_token").asText(null)
                ?: jsonNode.path("token").asText(null)

            if (!cardMask.isNullOrEmpty()) {
                client.cardMask = cardMask
                client.cardToken = cardToken ?: "sandbox_token_${jsonNode.path("payment_id").asText()}"
                clientRepository.save(client)

                messagingTemplate.convertAndSend(
                    "/topic/clients/$clientId/card-bind",
                    mapOf("status" to "SUCCESS", "cardMask" to cardMask)
                )
            } else {
                messagingTemplate.convertAndSend(
                    "/topic/clients/$clientId/card-bind",
                    mapOf("status" to "FAILED", "message" to "Missing card mask")
                )
            }
        } else {
            val clientId = orderReference.split("_").getOrNull(2)?.toLongOrNull()
            if (clientId != null) {
                messagingTemplate.convertAndSend(
                    "/topic/clients/$clientId/card-bind",
                    mapOf("status" to "FAILED", "message" to "Status: $status")
                )
            }
        }
        return ResponseEntity.ok("OK")
    }

    // Пополнение баланса водителя (поиск по индексу БД вместо findAll())
    private fun handleTopUpCallback(orderReference: String, status: String): ResponseEntity<String> {
        val payment = paymentRepository.findByExternalReference(orderReference)
            ?: return ResponseEntity.ok("Payment not found")

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

            try {
                messagingTemplate.convertAndSend(
                    "/topic/drivers/${driver.id}/balance-update",
                    mapOf("status" to "SUCCESS", "balance" to driver.balance, "added" to payment.amount)
                )
            } catch (_: Exception) {}

            try {
                notificationService.saveAndSend(
                    driver = driver,
                    title = "Баланс поповнено",
                    body = "Ваш баланс поповнено на ${String.format("%.2f", payment.amount)} UAH",
                    type = "PAYMENT"
                )
            } catch (_: Exception) {}

            return ResponseEntity.ok(mapOf("status" to "SUCCESS", "message" to "Оплата успішна!"))
        } else if (status == "failure" || status == "error" || status == "reversed") {
            payment.status = PaymentStatus.FAILED
            paymentRepository.save(payment)

            try {
                messagingTemplate.convertAndSend(
                    "/topic/drivers/${payment.driver.id}/balance-update",
                    mapOf("status" to "FAILED", "message" to "Оплата відхилена ($status)")
                )
            } catch (_: Exception) {}

            return ResponseEntity.ok(mapOf("status" to "FAILED", "message" to "Оплата відхилена ($status)"))
        }

        return ResponseEntity.ok(mapOf("status" to "PENDING", "message" to "Статус: $status"))
    }
}