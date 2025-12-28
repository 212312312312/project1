package com.taxiapp.server.service

import com.taxiapp.server.dto.promo.CreatePromoCodeRequest
import com.taxiapp.server.dto.promo.PromoCodeDto
import com.taxiapp.server.model.promo.PromoCode
import com.taxiapp.server.model.promo.PromoUsage
import com.taxiapp.server.model.user.Client
import com.taxiapp.server.repository.PromoCodeRepository
import com.taxiapp.server.repository.PromoUsageRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime

@Service
class PromoCodeService(
    private val promoCodeRepository: PromoCodeRepository,
    private val promoUsageRepository: PromoUsageRepository
) {

    // --- АДМИН: Получить список всех кодов ---
    fun getAllPromoCodes(): List<PromoCodeDto> {
        return promoCodeRepository.findAll().map { promo ->
            PromoCodeDto(
                id = promo.id,
                code = promo.code,
                discountPercent = promo.discountPercent,
                maxDiscountAmount = promo.maxDiscountAmount,
                usageLimit = promo.usageLimit,
                usedCount = promo.usedCount,
                expiresAt = promo.expiresAt,
                
                // НОВОЕ: Передаем админу инфу про длительность
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
            
            // НОВОЕ: Сохраняем настройку длительности
            activationDurationHours = req.durationHours 
        )
        promoCodeRepository.save(promo)
    }

    // --- КЛИЕНТ: Активация кода ---
    @Transactional
    fun activatePromoCode(client: Client, codeStr: String) {
        val code = codeStr.uppercase().trim()
        val promo = promoCodeRepository.findByCode(code)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Промокод не знайдено") }

        if (promo.expiresAt != null && LocalDateTime.now().isAfter(promo.expiresAt)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Термін дії коду вичерпано")
        }
        if (promo.usageLimit != null && promo.usedCount >= promo.usageLimit) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Ліміт активацій цього коду вичерпано")
        }
        if (promoUsageRepository.existsByClientAndPromoCodeId(client, promo.id)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Ви вже використовували цей промокод")
        }
        
        // --- ИСПРАВЛЕНИЕ: ActivatedAt -> CreatedAt ---
        val existingActive = promoUsageRepository.findFirstByClientAndIsUsedFalseOrderByCreatedAtDesc(client)
        if (existingActive.isPresent) {
             throw ResponseStatusException(HttpStatus.CONFLICT, "У вас вже є активний промокод. Використайте його спочатку.")
        }

        promo.usedCount += 1
        promoCodeRepository.save(promo)

        // НОВОЕ: Вычисляем персональный дедлайн
        val personalDeadline = promo.activationDurationHours?.let {
            LocalDateTime.now().plusHours(it.toLong())
        }

        val usage = PromoUsage(
            client = client,
            promoCode = promo,
            isUsed = false,
            
            // НОВОЕ: Записываем, когда скидка сгорит у этого юзера
            expiresAt = personalDeadline 
        )
        promoUsageRepository.save(usage)
    }

    // Внутренние методы
    fun findActiveUsage(client: Client): PromoUsage? {
        // --- ИСПРАВЛЕНИЕ: ActivatedAt -> CreatedAt ---
        return promoUsageRepository.findFirstByClientAndIsUsedFalseOrderByCreatedAtDesc(client).orElse(null)
    }

    @Transactional
    fun markAsUsed(usageId: Long) {
        val usage = promoUsageRepository.findById(usageId).orElse(null) ?: return
        usage.isUsed = true
        usage.usedAt = LocalDateTime.now()
        promoUsageRepository.save(usage)
    }

    @Transactional // Обов'язково додайте @Transactional, бо ми робимо delete
    fun deletePromoCode(id: Long) {
        if (!promoCodeRepository.existsById(id)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Промокод не знайдено")
        }

        // 1. Спочатку видаляємо історію використань цього коду клієнтами
        promoUsageRepository.deleteAllByPromoCodeId(id)

        // 2. Тепер видаляємо сам код (база даних вже не буде сваритися)
        promoCodeRepository.deleteById(id)
    }
}