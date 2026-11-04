package com.taxiapp.server.service

import com.taxiapp.server.dto.promo.CreatePromoCodeRequest
import com.taxiapp.server.dto.promo.PromoCodeDto
import com.taxiapp.server.model.analytics.ClientAppAction
import com.taxiapp.server.model.promo.PromoCode
import com.taxiapp.server.model.promo.PromoUsage
import com.taxiapp.server.model.user.Client
import com.taxiapp.server.repository.ClientAppActionRepository
import com.taxiapp.server.repository.PromoCodeRepository
import com.taxiapp.server.repository.PromoUsageRepository
import com.taxiapp.server.repository.TaxiOrderRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime

@Service
class PromoCodeService(
    private val promoCodeRepository: PromoCodeRepository,
    private val promoUsageRepository: PromoUsageRepository,
    private val taxiOrderRepository: TaxiOrderRepository,
    private val clientAppActionRepository: ClientAppActionRepository
) {

    // --- АДМИН: Получить список всех кодов ---
    fun getAllPromoCodes(): List<PromoCodeDto> {
        return promoCodeRepository.findAll().map { promo ->
            val realUsedCount = promoUsageRepository.countUsedByPromoCodeId(promo.id).toInt()
            val totalActivations = promoUsageRepository.countByPromoCodeId(promo.id).toInt()
            PromoCodeDto(
                id = promo.id,
                code = promo.code,
                discountPercent = promo.discountPercent,
                maxDiscountAmount = promo.maxDiscountAmount,
                usageLimit = promo.usageLimit,
                usedCount = maxOf(realUsedCount, totalActivations),
                expiresAt = promo.expiresAt,
                activationDurationHours = promo.activationDurationHours
            )
        }
    }

    // --- АДМИН: Создание кода ---
    fun createPromoCode(req: CreatePromoCodeRequest) {
        if (promoCodeRepository.existsByCode(req.code.uppercase())) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Такий код вже існує")
        }

        val expirationDate = req.activeDays?.let { LocalDateTime.now().plusDays(it.toLong()) }

        val promo = PromoCode(
            code = req.code.uppercase(),
            discountPercent = req.discountPercent,
            maxDiscountAmount = req.maxDiscountAmount,
            usageLimit = req.usageLimit,
            expiresAt = expirationDate,
            activationDurationHours = req.durationHours
        )
        promoCodeRepository.save(promo)
    }

    // --- КЛИЕНТ: Активация кода (с антифрод-защитой по deviceId) ---
    @Transactional(noRollbackFor = [ResponseStatusException::class, RuntimeException::class])
    fun activatePromoCode(client: Client, codeStr: String, deviceId: String? = null) {
        val code = codeStr.uppercase().trim()
        val promo = promoCodeRepository.findByCode(code)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Промокод не знайдено") }

        if (promo.expiresAt != null && LocalDateTime.now().isAfter(promo.expiresAt)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Термін дії коду вичерпано")
        }

        // Антифрод проверка по deviceId (из запроса, профиля клиента или последнего заказа)
        val targetDeviceId = deviceId?.takeIf { it.isNotBlank() }
            ?: client.deviceId?.takeIf { it.isNotBlank() }
            ?: taxiOrderRepository.findLatestDeviceIdByClientId(client.id!!)

        if (!targetDeviceId.isNullOrBlank()) {
            val deviceAlreadyUsed = promoUsageRepository.existsByDeviceIdAndClientIdNot(targetDeviceId, client.id!!)
            if (deviceAlreadyUsed) {
                clientAppActionRepository.save(
                    ClientAppAction(
                        clientId = client.id!!,
                        actionName = "promo_blocked_device",
                        actionValue = "code:$code,device:$targetDeviceId",
                        sessionId = "server_antifraud",
                        createdAt = LocalDateTime.now()
                    )
                )
                throw ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Цей пристрій вже використовував промокод під іншим акаунтом"
                )
            }
        }

        val totalActivations = promoUsageRepository.countByPromoCodeId(promo.id)
        if (promo.usageLimit != null && totalActivations >= promo.usageLimit) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Ліміт активацій цього коду вичерпано")
        }
        if (promoUsageRepository.existsByClientAndPromoCodeId(client, promo.id)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Ви вже використовували цей промокод")
        }

        val existingActive = promoUsageRepository.findFirstByClientAndIsUsedFalseOrderByCreatedAtDesc(client)
        if (existingActive.isPresent) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "У вас вже є активний промокод. Використайте його спочатку.")
        }

        val personalDeadline = promo.activationDurationHours?.let {
            LocalDateTime.now().plusHours(it.toLong())
        }

        val usage = PromoUsage(
            client = client,
            promoCode = promo,
            isUsed = false,
            expiresAt = personalDeadline,
            deviceId = targetDeviceId
        )
        promoUsageRepository.save(usage)
    }

    fun findActiveUsage(client: Client): PromoUsage? {
        return promoUsageRepository.findFirstByClientAndIsUsedFalseOrderByCreatedAtDesc(client).orElse(null)
    }

    // ИСПРАВЛЕНО: инкрементируем promoCode.usedCount только при реальном завершении поездки со скидкой
    @Transactional
    fun markAsUsed(usageId: Long) {
        val usage = promoUsageRepository.findById(usageId).orElse(null) ?: return
        if (!usage.isUsed) {
            usage.isUsed = true
            usage.usedAt = LocalDateTime.now()
            promoUsageRepository.save(usage)

            val promo = usage.promoCode
            promo.usedCount += 1
            promoCodeRepository.save(promo)
        }
    }

    @Transactional
    fun deletePromoCode(id: Long) {
        if (!promoCodeRepository.existsById(id)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Промокод не знайдено")
        }
        promoUsageRepository.deleteAllByPromoCodeId(id)
        promoCodeRepository.deleteById(id)
    }
}