package com.taxiapp.server.repository

import com.taxiapp.server.model.driver.DriverActivityHistory
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface DriverActivityHistoryRepository : JpaRepository<DriverActivityHistory, Long> {
    // Получаем последние 50 записей для истории в приложении
    fun findTop50ByDriverIdOrderByCreatedAtDesc(driverId: Long): List<DriverActivityHistory>
}