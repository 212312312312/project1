package com.taxiapp.server.scheduler

import com.taxiapp.server.model.enums.PhotoControlStatus
import com.taxiapp.server.repository.DriverRepository
import com.taxiapp.server.repository.PhotoControlRepository
import com.taxiapp.server.service.NotificationService
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Component
class PhotoControlScheduler(
    private val photoControlRepository: PhotoControlRepository,
    private val driverRepository: DriverRepository,
    private val notificationService: NotificationService
) {

    // Проверка просроченных запросов каждые 60 секунд
    @Scheduled(fixedRate = 60000)
    @Transactional
    fun checkExpiredPhotoControls() {
        val now = LocalDateTime.now()
        val expiredList = photoControlRepository.findAllExpiredPending(now)

        for (pc in expiredList) {
            pc.status = PhotoControlStatus.EXPIRED
            photoControlRepository.save(pc)

            val driver = pc.driver
            driver.photoControlRestricted = true
            driverRepository.save(driver)

            notificationService.sendPhotoControlExpiredNotification(driver)
        }
    }
}