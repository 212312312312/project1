package com.taxiapp.server.repository

import com.taxiapp.server.model.promo.PromoPlan
import com.taxiapp.server.model.promo.PromoPlanType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.LocalDateTime
import java.util.Optional

@Repository
interface PromoPlanRepository : JpaRepository<PromoPlan, Long> {
    
    fun findFirstByIsActiveTrueAndStartDateBeforeAndEndDateAfter(
        now1: LocalDateTime, 
        now2: LocalDateTime
    ): Optional<PromoPlan>

    // ➕ Добавляем поиск активного плана конкретного типа
    fun findFirstByPlanTypeAndIsActiveTrueAndStartDateBeforeAndEndDateAfter(
        planType: PromoPlanType,
        now1: LocalDateTime, 
        now2: LocalDateTime
    ): Optional<PromoPlan>
}