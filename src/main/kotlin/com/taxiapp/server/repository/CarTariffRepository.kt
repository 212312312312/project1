package com.taxiapp.server.repository

import com.taxiapp.server.model.order.CarTariff
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface CarTariffRepository : JpaRepository<CarTariff, Long> {
    // Тут можна додати специфічні методи пошуку, якщо знадобляться
    // Выбираем только активные тарифы и сортируем их от меньшего sortOrder к большему
    fun findAllByIsActiveTrueOrderBySortOrderAsc(): List<CarTariff>
}