package com.taxiapp.server.controller

import com.taxiapp.server.dto.driver.*
import com.taxiapp.server.model.enums.PhotoControlStatus
import com.taxiapp.server.repository.DriverRepository
import com.taxiapp.server.service.FileStorageService
import com.taxiapp.server.service.PhotoControlService
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartHttpServletRequest
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/v1/photo-control")
class PhotoControlController(
    private val photoControlService: PhotoControlService,
    private val driverRepository: DriverRepository,
    private val fileStorageService: FileStorageService
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

    // Водитель (через мобильный WebView или с JWT): Отправить фотографии фотоконтроля
    @PostMapping("/driver/{id}/submit", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun submitPhotos(
        @PathVariable id: Long,
        @RequestParam(required = false) driverId: Long?,
        @AuthenticationPrincipal userDetails: UserDetails?,
        request: MultipartHttpServletRequest
    ): ResponseEntity<PhotoControlStatusDto> {
        val authenticatedDriver = userDetails?.let {
            driverRepository.findByUserPhone(it.username) ?: driverRepository.findByUserLogin(it.username)
        }

        val resolvedDriverId = authenticatedDriver?.id ?: driverId
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Водія не знайдено")

        // Если запрос делает авторизованный водитель, запрещаем подменять чужой driverId
        if (authenticatedDriver != null && driverId != null && authenticatedDriver.id != driverId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Доступ до чужого фотоконтролю заборонено")
        }

        // ЗАЩИТА ДИСКА: Проверяем ДО записи любых файлов на диск, что у водителя реально открыт этот фотоконтроль
        // и он находится в статусе ожидания фото (PENDING или REJECTED)
        val activePc = photoControlService.getActivePhotoControl(resolvedDriverId)
        if (activePc == null || activePc.id != id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Активний запит фотоконтролю не знайдено або доступ заборонено")
        }
        if (activePc.status != PhotoControlStatus.PENDING && activePc.status != PhotoControlStatus.REJECTED) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Фотографії для цього запиту вже відправлені на перевірку")
        }

        val savedPhotos = mutableMapOf<String, String>()
        val allowedExtensions = setOf("jpg", "jpeg", "png", "webp")

        request.fileMap.forEach { (key, file) ->
            if (!file.isEmpty) {
                val originalFilename = file.originalFilename ?: "file"
                val extension = originalFilename.substringAfterLast('.', "").lowercase()

                if (extension !in allowedExtensions) {
                    throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Недопустимий тип файлу: .$extension")
                }

                savedPhotos[key] = fileStorageService.storeFile(file)
            }
        }

        val dto = SubmitPhotoControlDto(
            frontUrl = savedPhotos["carFront"] ?: savedPhotos["front"] ?: "",
            backUrl = savedPhotos["carBack"] ?: savedPhotos["back"] ?: "",
            leftUrl = savedPhotos["carLeft"] ?: savedPhotos["left"] ?: "",
            rightUrl = savedPhotos["carRight"] ?: savedPhotos["right"] ?: "",
            interiorFrontUrl = savedPhotos["carInteriorFront"] ?: savedPhotos["interiorFront"] ?: "",
            interiorBackUrl = savedPhotos["carInteriorBack"] ?: savedPhotos["interiorBack"] ?: ""
        )

        return ResponseEntity.ok(photoControlService.submitPhotos(resolvedDriverId, id, dto))
    }
}