package com.taxiapp.server.service

import com.taxiapp.server.dto.driver.*
import com.taxiapp.server.model.driver.PhotoControl
import com.taxiapp.server.model.enums.PhotoControlStatus
import com.taxiapp.server.repository.DriverRepository
import com.taxiapp.server.repository.PhotoControlRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class PhotoControlService(
    private val photoControlRepository: PhotoControlRepository,
    private val driverRepository: DriverRepository,
    private val notificationService: NotificationService
) {

    @Transactional
    fun requestPhotoControl(driverId: Long): PhotoControlStatusDto {
        val driver = driverRepository.findById(driverId)
            .orElseThrow { IllegalArgumentException("Driver not found") }

        val photoControl = photoControlRepository.save(
            PhotoControl(driver = driver)
        )

        driver.activePhotoControlId = photoControl.id
        driverRepository.save(driver)

        notificationService.sendPhotoControlRequestNotification(driver, photoControl.deadlineAt)

        return photoControl.toDto()
    }

    @Transactional
    fun submitPhotos(driverId: Long, photoControlId: Long, dto: SubmitPhotoControlDto): PhotoControlStatusDto {
        val photoControl = photoControlRepository.findById(photoControlId)
            .orElseThrow { IllegalArgumentException("PhotoControl request not found") }

        if (photoControl.driver.id != driverId) {
            throw IllegalArgumentException("Access denied")
        }

        photoControl.frontUrl = dto.frontUrl
        photoControl.backUrl = dto.backUrl
        photoControl.leftUrl = dto.leftUrl
        photoControl.rightUrl = dto.rightUrl
        photoControl.interiorFrontUrl = dto.interiorFrontUrl
        photoControl.interiorBackUrl = dto.interiorBackUrl
        photoControl.submittedAt = LocalDateTime.now()
        photoControl.status = PhotoControlStatus.SUBMITTED

        return photoControlRepository.save(photoControl).toDto()
    }

    @Transactional
    fun reviewPhotoControl(
        photoControlId: Long, 
        dispatcherId: Long, 
        dto: ReviewPhotoControlDto
    ): PhotoControlStatusDto {
        val photoControl = photoControlRepository.findById(photoControlId)
            .orElseThrow { IllegalArgumentException("PhotoControl request not found") }

        val driver = photoControl.driver

        if (dto.approved) {
            photoControl.status = PhotoControlStatus.APPROVED
            driver.photoControlRestricted = false
            driver.activePhotoControlId = null
            notificationService.sendPhotoControlApprovedNotification(driver)
        } else {
            photoControl.status = PhotoControlStatus.REJECTED
            photoControl.rejectReason = dto.rejectReason
            driver.photoControlRestricted = true
            notificationService.sendPhotoControlRejectedNotification(driver, dto.rejectReason)
        }

        photoControl.reviewedAt = LocalDateTime.now()
        photoControl.reviewedByDispatcherId = dispatcherId

        driverRepository.save(driver)
        return photoControlRepository.save(photoControl).toDto()
    }

    fun getActivePhotoControl(driverId: Long): PhotoControlStatusDto? {
        val driver = driverRepository.findById(driverId).orElse(null) ?: return null
        val activeId = driver.activePhotoControlId ?: return null
        return photoControlRepository.findById(activeId).map { it.toDto() }.orElse(null)
    }

    fun getAllPhotoControls(): List<PhotoControlStatusDto> {
        return photoControlRepository.findAllByOrderByRequestedAtDesc().map { it.toDto() }
    }

    @Transactional
fun cancelPhotoControl(photoControlId: Long): PhotoControlStatusDto {
    val photoControl = photoControlRepository.findById(photoControlId)
        .orElseThrow { IllegalArgumentException("PhotoControl request not found") }

    val driver = photoControl.driver
    photoControl.status = PhotoControlStatus.CANCELLED
    driver.activePhotoControlId = null
    driver.photoControlRestricted = false

    driverRepository.save(driver)
    return photoControlRepository.save(photoControl).toDto()
}

    private fun PhotoControl.toDto() = PhotoControlStatusDto(
        id = this.id,
        driverId = this.driver.id,
        driverName = this.driver.fullName,
        status = this.status,
        deadlineAt = this.deadlineAt,
        photoControlRestricted = this.driver.photoControlRestricted,
        frontUrl = this.frontUrl,
        backUrl = this.backUrl,
        leftUrl = this.leftUrl,
        rightUrl = this.rightUrl,
        interiorFrontUrl = this.interiorFrontUrl,
        interiorBackUrl = this.interiorBackUrl,
        rejectReason = this.rejectReason
    )
}