package com.taxiapp.server.controller

import com.taxiapp.server.dto.driver.*
import com.taxiapp.server.repository.DriverRepository
import com.taxiapp.server.service.PhotoControlService
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/photo-control")
class PhotoControlController(
    private val photoControlService: PhotoControlService,
    private val driverRepository: DriverRepository
) {

    // Диспетчер: Запросить фотоконтроль
    @PostMapping("/request")
    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMINISTRATOR')")
    fun requestPhotoControl(@RequestBody dto: RequestPhotoControlDto): ResponseEntity<PhotoControlStatusDto> {
        return ResponseEntity.ok(photoControlService.requestPhotoControl(dto.driverId))
    }

    // Диспетчер: Получить список всех заявок
    @GetMapping("/admin/all")
    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMINISTRATOR')")
    fun getAllPhotoControls(): ResponseEntity<List<PhotoControlStatusDto>> {
        return ResponseEntity.ok(photoControlService.getAllPhotoControls())
    }

    // Диспетчер: Апрув/Реджект
    @PostMapping("/admin/{id}/review")
    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMINISTRATOR')")
    fun reviewPhotoControl(
        @PathVariable id: Long,
        @AuthenticationPrincipal userDetails: UserDetails,
        @RequestBody dto: ReviewPhotoControlDto
    ): ResponseEntity<PhotoControlStatusDto> {
        val dispatcherId = userDetails.username.toLongOrNull() ?: 1L
        return ResponseEntity.ok(photoControlService.reviewPhotoControl(id, dispatcherId, dto))
    }

    // Водитель: Получить активный статус фотоконтроля
    @GetMapping("/driver/active")
    @PreAuthorize("hasRole('DRIVER')")
    fun getActiveForDriver(@AuthenticationPrincipal userDetails: UserDetails): ResponseEntity<PhotoControlStatusDto?> {
        val driver = driverRepository.findByUserPhone(userDetails.username)
            ?: driverRepository.findByUserLogin(userDetails.username)
            ?: return ResponseEntity.ok(null)

        return ResponseEntity.ok(photoControlService.getActivePhotoControl(driver.id))
    }

    // Диспетчер: Отменить запрос фотоконтроля
    @PostMapping("/admin/{id}/cancel")
    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMINISTRATOR')")
    fun cancelPhotoControl(@PathVariable id: Long): ResponseEntity<PhotoControlStatusDto> {
        return ResponseEntity.ok(photoControlService.cancelPhotoControl(id))
    }

    // Водитель / WebView: Отправить 6 фото
    @PostMapping("/driver/{id}/submit")
    fun submitPhotos(
        @PathVariable id: Long,
        @RequestParam driverId: Long,
        @RequestBody dto: SubmitPhotoControlDto
    ): ResponseEntity<PhotoControlStatusDto> {
        return ResponseEntity.ok(photoControlService.submitPhotos(driverId, id, dto))
    }
}