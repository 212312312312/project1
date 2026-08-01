package com.taxiapp.server.controller

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.taxiapp.server.dto.auth.MessageResponse
import com.taxiapp.server.dto.auth.RegisterDriverRequest
import com.taxiapp.server.dto.driver.DriverDto
import com.taxiapp.server.dto.driver.TempBlockRequest
import com.taxiapp.server.dto.driver.UpdateDriverRequest
import com.taxiapp.server.model.enums.RegistrationStatus
import com.taxiapp.server.repository.DriverRepository
import com.taxiapp.server.service.DriverAdminService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/v1/admin/drivers")
class DriverAdminController(
    private val driverAdminService: DriverAdminService,
    private val driverRepository: DriverRepository
) {

    // 1. СПИСОК "ВСІ ВОДІЇ"
    @GetMapping
    @PreAuthorize("hasAnyAuthority('ADMINISTRATOR', 'ROLE_ADMINISTRATOR')")
    fun getAllDrivers(): ResponseEntity<List<DriverDto>> {
        val drivers = driverRepository.findAllByRegistrationStatusNot(RegistrationStatus.PENDING)
            .sortedBy { it.id }
            .map { DriverDto(it) }
            
        return ResponseEntity.ok(drivers)
    }

    // 1.1. СПИСОК "В ЧЕРЗІ НА ВИДАЛЕННЯ"
    @GetMapping("/pending-deletion")
    @PreAuthorize("hasAnyAuthority('ADMINISTRATOR', 'ROLE_ADMINISTRATOR', 'DISPATCHER', 'ROLE_DISPATCHER')")
    fun getDriversPendingDeletion(): ResponseEntity<List<DriverDto>> {
        val drivers = driverRepository.findAllPendingDeletion().map { DriverDto(it) }
        return ResponseEntity.ok(drivers)
    }

    // CREATE
    @PostMapping(consumes = ["multipart/form-data"])
    @PreAuthorize("hasAnyAuthority('ADMINISTRATOR', 'ROLE_ADMINISTRATOR')")
    fun createDriver(
        @RequestPart("request") requestJson: String,
        @RequestPart("file", required = false) file: MultipartFile?,
        @RequestPart("carPhoto", required = false) carPhoto: MultipartFile?,
        @RequestPart("techPassportFront", required = false) techPassportFront: MultipartFile?,
        @RequestPart("techPassportBack", required = false) techPassportBack: MultipartFile?,
        @RequestPart("insurancePhoto", required = false) insurancePhoto: MultipartFile?,
        @RequestPart("photoFront", required = false) photoFront: MultipartFile?,
        @RequestPart("photoBack", required = false) photoBack: MultipartFile?,
        @RequestPart("photoLeft", required = false) photoLeft: MultipartFile?,
        @RequestPart("photoRight", required = false) photoRight: MultipartFile?,
        @RequestPart("photoSeatsFront", required = false) photoSeatsFront: MultipartFile?,
        @RequestPart("photoSeatsBack", required = false) photoSeatsBack: MultipartFile?
    ): ResponseEntity<MessageResponse> {
        val mapper = jacksonObjectMapper()
        val request = mapper.readValue(requestJson, RegisterDriverRequest::class.java)
        val carFiles = collectCarFiles(carPhoto, techPassportFront, techPassportBack, insurancePhoto, photoFront, photoBack, photoLeft, photoRight, photoSeatsFront, photoSeatsBack)
        val response = driverAdminService.createDriver(request, file, carFiles)
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }
    
    // UPDATE
    @PutMapping("/{id}", consumes = ["multipart/form-data"])
    @PreAuthorize("hasAnyAuthority('ADMINISTRATOR', 'ROLE_ADMINISTRATOR')")
    fun updateDriver(
        @PathVariable id: Long, 
        @RequestPart("request") requestJson: String,
        @RequestPart("file", required = false) file: MultipartFile?,
        @RequestPart("carPhoto", required = false) carPhoto: MultipartFile?,
        @RequestPart("techPassportFront", required = false) techPassportFront: MultipartFile?,
        @RequestPart("techPassportBack", required = false) techPassportBack: MultipartFile?,
        @RequestPart("insurancePhoto", required = false) insurancePhoto: MultipartFile?,
        @RequestPart("photoFront", required = false) photoFront: MultipartFile?,
        @RequestPart("photoBack", required = false) photoBack: MultipartFile?,
        @RequestPart("photoLeft", required = false) photoLeft: MultipartFile?,
        @RequestPart("photoRight", required = false) photoRight: MultipartFile?,
        @RequestPart("photoSeatsFront", required = false) photoSeatsFront: MultipartFile?,
        @RequestPart("photoSeatsBack", required = false) photoSeatsBack: MultipartFile?
    ): ResponseEntity<DriverDto> {
        val mapper = jacksonObjectMapper()
        val request = mapper.readValue(requestJson, UpdateDriverRequest::class.java)
        val carFiles = collectCarFiles(carPhoto, techPassportFront, techPassportBack, insurancePhoto, photoFront, photoBack, photoLeft, photoRight, photoSeatsFront, photoSeatsBack)
        return ResponseEntity.ok(driverAdminService.updateDriver(id, request, file, carFiles))
    }

    private fun collectCarFiles(
        carPhoto: MultipartFile?, techFront: MultipartFile?, techBack: MultipartFile?, ins: MultipartFile?,
        pFront: MultipartFile?, pBack: MultipartFile?, pLeft: MultipartFile?, pRight: MultipartFile?,
        sFront: MultipartFile?, sBack: MultipartFile?
    ): Map<String, MultipartFile> {
        val map = mutableMapOf<String, MultipartFile>()
        carPhoto?.let { map["carPhoto"] = it }
        techFront?.let { map["techPassportFront"] = it }
        techBack?.let { map["techPassportBack"] = it }
        ins?.let { map["insurancePhoto"] = it }
        pFront?.let { map["photoFront"] = it }
        pBack?.let { map["photoBack"] = it }
        pLeft?.let { map["photoLeft"] = it }
        pRight?.let { map["photoRight"] = it }
        sFront?.let { map["photoSeatsFront"] = it }
        sBack?.let { map["photoSeatsBack"] = it }
        return map
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ADMINISTRATOR', 'ROLE_ADMINISTRATOR')")
    fun deleteDriver(@PathVariable id: Long): ResponseEntity<MessageResponse> {
        return ResponseEntity.ok(driverAdminService.deleteDriver(id))
    }

    @PostMapping("/{id}/temp-block")
    @PreAuthorize("hasAnyAuthority('ADMINISTRATOR', 'ROLE_ADMINISTRATOR')")
    fun tempBlockDriver(@PathVariable id: Long, @RequestBody request: TempBlockRequest): ResponseEntity<DriverDto> {
        return ResponseEntity.ok(driverAdminService.blockDriverTemporarily(id, request))
    }

    @PatchMapping("/{id}/block")
    @PreAuthorize("hasAnyAuthority('ADMINISTRATOR', 'ROLE_ADMINISTRATOR')")
    fun blockDriverPerm(@PathVariable id: Long): ResponseEntity<DriverDto> {
        return ResponseEntity.ok(driverAdminService.blockDriverPermanently(id))
    }

    @PatchMapping("/{id}/unblock")
    @PreAuthorize("hasAnyAuthority('ADMINISTRATOR', 'ROLE_ADMINISTRATOR')")
    fun unblockDriver(@PathVariable id: Long): ResponseEntity<DriverDto> {
        return ResponseEntity.ok(driverAdminService.unblockDriver(id))
    }

    // ЄДИНИЙ ТА УНІВЕРСАЛЬНИЙ ЕНДПОІНТ ДЛЯ СХВАЛЕННЯ РЕЄСТРАЦІЇ
    @PostMapping("/{id}/approve-registration")
    @PreAuthorize("hasAnyAuthority('ADMINISTRATOR', 'ROLE_ADMINISTRATOR', 'DISPATCHER', 'ROLE_DISPATCHER')")
    fun approveDriver(
        @PathVariable id: Long, 
        @RequestBody(required = false) tariffIds: List<Long>?
    ): ResponseEntity<Void> {
        driverAdminService.approveDriverRegistration(id, tariffIds ?: emptyList())
        return ResponseEntity.ok().build()
    }

    @PostMapping("/{id}/activity")
    @PreAuthorize("hasAnyAuthority('ADMINISTRATOR', 'ROLE_ADMINISTRATOR')")
    fun updateActivity(@PathVariable id: Long, @RequestBody request: ChangeActivityRequest): ResponseEntity<DriverDto> {
        return ResponseEntity.ok(driverAdminService.updateDriverActivity(id, request.points, request.reason))
    }
    
    // --- РОБОТА З АВТОМОБІЛЯМИ ---

    @GetMapping("/cars/pending")
@PreAuthorize("hasAnyAuthority('ADMINISTRATOR', 'ROLE_ADMINISTRATOR')")
fun getPendingCars(): List<DriverAdminService.PendingCarDto> {
    return driverAdminService.getPendingCars()
}

    @PostMapping("/cars/{id}/approve")
    @PreAuthorize("hasAnyAuthority('ADMINISTRATOR', 'ROLE_ADMINISTRATOR')")
    fun approveCar(@PathVariable id: Long): ResponseEntity<MessageResponse> {
        return ResponseEntity.ok(driverAdminService.approveCar(id))
    }

    @PostMapping("/cars/{id}/reject")
    @PreAuthorize("hasAnyAuthority('ADMINISTRATOR', 'ROLE_ADMINISTRATOR')")
    fun rejectCar(@PathVariable id: Long, @RequestBody reason: String): ResponseEntity<MessageResponse> {
        return ResponseEntity.ok(driverAdminService.rejectCar(id, reason))
    }

    @PutMapping("/cars/{id}")
    @PreAuthorize("hasAnyAuthority('ADMINISTRATOR', 'ROLE_ADMINISTRATOR')")
    fun updateCarDetails(@PathVariable id: Long, @RequestBody request: com.taxiapp.server.dto.driver.CarDto): ResponseEntity<Any> {
        driverAdminService.updateCarDetails(id, request)
        return ResponseEntity.ok(mapOf("message" to "Дані авто оновлено"))
    }

    // --- РОБОТА З ЧЕРГОЮ РЕЄСТРАЦІЙ ---

    @GetMapping("/pending-registration")
    @PreAuthorize("hasAnyAuthority('ADMINISTRATOR', 'ROLE_ADMINISTRATOR', 'DISPATCHER', 'ROLE_DISPATCHER')")
    fun getPendingDrivers(): List<DriverDto> {
        return driverRepository.findAllByRegistrationStatus(RegistrationStatus.PENDING)
            .map { DriverDto(it) }
    }

    @PostMapping("/{id}/reject-registration")
    @PreAuthorize("hasAnyAuthority('ADMINISTRATOR', 'ROLE_ADMINISTRATOR', 'DISPATCHER', 'ROLE_DISPATCHER')")
    fun rejectDriver(@PathVariable id: Long, @RequestBody reason: String): ResponseEntity<Void> {
        driverAdminService.rejectDriverRegistration(id, reason)
        return ResponseEntity.ok().build()
    }

    // =========================================================================
    // 💰 ФІНАНСОВІ ЕНДПОІНТИ
    // =========================================================================

    @GetMapping("/{id}/transactions")
    @PreAuthorize("hasAnyAuthority('ADMINISTRATOR', 'ROLE_ADMINISTRATOR')")
    fun getDriverTransactions(@PathVariable id: Long): ResponseEntity<List<Map<String, Any>>> {
        val transactions = driverAdminService.getDriverTransactions(id)
        val dtos = transactions.map { tx ->
            mapOf(
                "id" to (tx.id ?: 0L),
                "amount" to tx.amount,
                "operationType" to tx.operationType,
                "description" to (tx.description ?: ""),
                "createdAt" to tx.createdAt.toString()
            )
        }
        return ResponseEntity.ok(dtos)
    }

    data class BalanceUpdateRequest(val amount: Double, val description: String)

    @PostMapping("/{id}/balance")
    @PreAuthorize("hasAnyAuthority('ADMINISTRATOR', 'ROLE_ADMINISTRATOR')")
    fun updateBalance(
        @PathVariable id: Long,
        @RequestBody request: BalanceUpdateRequest
    ): ResponseEntity<DriverDto> {
        val updatedDriver = driverAdminService.manualBalanceUpdate(id, request.amount, request.description)
        return ResponseEntity.ok(updatedDriver)
    }
}

data class ChangeActivityRequest(
    val points: Int,
    val reason: String
)