package com.taxiapp.server.repository

import com.taxiapp.server.model.promo.ClientPromoProgress
import java.time.LocalDateTime
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface ClientPromoProgressRepository : JpaRepository<ClientPromoProgress, Long> {
    // Знайти прогрес клієнта по конкретному завданню
    fun findByClientIdAndPromoTaskId(clientId: Long, promoTaskId: Long): Optional<ClientPromoProgress>
    
    // Знайти всі прогреси клієнта
    fun findAllByClientId(clientId: Long): List<ClientPromoProgress>

    fun findAllByIsRewardAvailableTrueAndRewardExpiresAtBetween(start: LocalDateTime, end: LocalDateTime): List<ClientPromoProgress>
    
    // Знайти активну нагороду (знижку), якщо вона є
    fun findFirstByClientIdAndIsRewardAvailableTrue(clientId: Long): Optional<ClientPromoProgress>

    fun countByPromoTaskId(promoTaskId: Long): Int
}