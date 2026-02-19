package com.taxiapp.server.repository

import com.taxiapp.server.model.notification.DriverNotification
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface DriverNotificationRepository : JpaRepository<DriverNotification, Long> {
    // Получить уведомления водителя, отсортированные от новых к старым
    fun findAllByDriverIdOrderByCreatedAtDesc(driverId: Long): List<DriverNotification>
}