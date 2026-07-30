package com.taxiapp.server.repository

import com.taxiapp.server.model.promo.PromoPlan
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.LocalDateTime
import java.util.Optional

@Repository
interface PromoPlanRepository : JpaRepository<PromoPlan, Long> {
    
    // Запрос проверяет: startDate <= текущее_время <= endDate AND isActive = true
    fun findFirstByIsActiveTrueAndStartDateBeforeAndEndDateAfter(
        now1: LocalDateTime, 
        now2: LocalDateTime
    ): Optional<PromoPlan>
}