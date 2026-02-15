package com.taxiapp.server.controller

import com.taxiapp.server.model.enums.TransactionType
import com.taxiapp.server.model.finance.PaymentStatus
import com.taxiapp.server.model.finance.PaymentTransaction
import com.taxiapp.server.model.finance.WalletTransaction
import com.taxiapp.server.model.user.Driver
import com.taxiapp.server.model.user.User
import com.taxiapp.server.repository.DriverRepository
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
import java.util.UUID

@RestController
@RequestMapping("/api/v1/payments")
class PaymentController(
    private val paymentRepository: PaymentTransactionRepository,
    private val driverRepository: DriverRepository,
    private val walletTransactionRepository: WalletTransactionRepository,
    private val liqPayService: LiqPayService
) {

    data class InitPaymentRequest(val amount: Double)
    data class InitPaymentResponse(val paymentUrl: String, val paymentId: Long)

    // 1. Инициализация
    @PostMapping("/init")
    fun initPayment(@AuthenticationPrincipal user: User, @RequestBody request: InitPaymentRequest): ResponseEntity<InitPaymentResponse> {
        // --- ИСПРАВЛЕНИЕ: Надежное получение водителя ---
        val driver = driverRepository.findById(user.id!!)
            .orElseThrow { ResponseStatusException(HttpStatus.FORBIDDEN, "Водій не знайдений") }
        
        // Дополнительная проверка, что это именно водитель (если в базе есть разделение)
        // Но так как мы ищем через driverRepository, это уже гарантировано будет Driver или ошибка.
        // ------------------------------------------------

        if (request.amount < 1.0) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Мінімальна сума 1 грн")

        // Генерируем уникальный ID для LiqPay
        val orderReference = "taxi_topup_${driver.id}_${System.currentTimeMillis()}"

        val payment = PaymentTransaction(
            driver = driver,
            amount = request.amount,
            status = PaymentStatus.PENDING,
            externalReference = orderReference
        )
        val savedPayment = paymentRepository.save(payment)

        // Генерируем ссылку
        val url = liqPayService.generateCheckoutUrl(
            orderId = orderReference,
            amount = request.amount,
            description = "Поповнення балансу водія ID ${driver.id}"
        )
        
        return ResponseEntity.ok(InitPaymentResponse(url, savedPayment.id!!))
    }

    // 2. Проверка статуса
    @PostMapping("/check/{paymentId}")
    @Transactional
    fun checkStatus(@AuthenticationPrincipal user: User, @PathVariable paymentId: Long): ResponseEntity<Map<String, String>> {
        val payment = paymentRepository.findById(paymentId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }

        // Здесь тоже лучше сравнить ID
        if (payment.driver.id != user.id) throw ResponseStatusException(HttpStatus.FORBIDDEN)
        
        if (payment.status == PaymentStatus.SUCCESS) {
            return ResponseEntity.ok(mapOf("status" to "SUCCESS", "message" to "Вже оплачено"))
        }

        val liqPayStatus = liqPayService.getStatus(payment.externalReference!!)
        
        // Добавляем статус 'wait_accept' (часто бывает в LiqPay при ручном подтверждении)
        if (liqPayStatus == "success" || liqPayStatus == "sandbox" || liqPayStatus == "wait_accept") {
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

            return ResponseEntity.ok(mapOf("status" to "SUCCESS", "message" to "Оплата успішна!"))
        } else if (liqPayStatus == "failure" || liqPayStatus == "error" || liqPayStatus == "reversed") {
            payment.status = PaymentStatus.FAILED
            paymentRepository.save(payment)
            return ResponseEntity.ok(mapOf("status" to "FAILED", "message" to "Оплата відхилена ($liqPayStatus)"))
        }

        return ResponseEntity.ok(mapOf("status" to "PENDING", "message" to "Статус: $liqPayStatus"))
    }
}