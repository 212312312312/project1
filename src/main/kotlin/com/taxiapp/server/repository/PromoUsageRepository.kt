package com.taxiapp.server.repository

import com.taxiapp.server.model.promo.PromoUsage
import com.taxiapp.server.model.user.Client
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.LocalDateTime
import java.util.Optional

interface PromoUsageRepository : JpaRepository<PromoUsage, Long> {
    
    // --- ЦЕЙ МЕТОД БУВ ВІДСУТНІЙ І ВИКЛИКАВ ПОМИЛКУ ---
    fun findAllByClient(client: Client): List<PromoUsage>
    
    // Інші твої методи
    fun existsByClientAndPromoCodeId(client: Client, promoCodeId: Long): Boolean

    fun findAllByIsUsedFalseAndExpiresAtBetween(start: LocalDateTime, end: LocalDateTime): List<PromoUsage>
    
    fun countByPromoCodeId(promoCodeId: Long): Long

    fun findFirstByClientAndIsUsedFalseOrderByCreatedAtDesc(client: Client): Optional<PromoUsage>

    fun findAllByClientAndIsUsedFalse(client: Client): List<PromoUsage>

    // Виправлено activatedAt -> createdAt
    @Query("SELECT p FROM PromoUsage p WHERE p.client = :client AND p.isUsed = false AND (p.expiresAt IS NULL OR p.expiresAt > CURRENT_TIMESTAMP) ORDER BY p.createdAt DESC")
    fun findActiveValidUsage(client: Client): Optional<PromoUsage>

    fun deleteAllByPromoCodeId(promoCodeId: Long)

    // ➕ Проверка и подсчет попыток абуза скидок по deviceId:
fun existsByDeviceIdAndClientIdNot(deviceId: String, clientId: Long): Boolean

fun countByDeviceIdAndIsUsedTrue(deviceId: String): Long
}