package com.taxiapp.server.repository

import com.taxiapp.server.model.promo.PromoTask
import org.springframework.data.jpa.repository.JpaRepository

interface PromoTaskRepository : JpaRepository<PromoTask, Long> {
    fun findAllByIsActiveTrue(): List<PromoTask> // Знайти всі активні акції
}