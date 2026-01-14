package com.taxiapp.server.controller

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.taxiapp.server.dto.auth.MessageResponse
import com.taxiapp.server.dto.auth.RegisterDriverRequest
import com.taxiapp.server.dto.driver.DriverDto
import com.taxiapp.server.dto.driver.TempBlockRequest
import com.taxiapp.server.dto.driver.UpdateDriverRequest
import com.taxiapp.server.service.DriverAdminService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/v1/admin/drivers")
class DriverAdminController(
    private val driverAdminService: DriverAdminService
) {

    @GetMapping
    fun getAllDrivers(): ResponseEntity<List<DriverDto>> {
        return ResponseEntity.ok(driverAdminService.getAllDrivers())
    }

    // CREATE
    @PostMapping(consumes = ["multipart/form-data"])
    fun createDriver(
        @RequestPart("request") requestJson: String,
        @RequestPart("file", required = false) file: MultipartFile?,       // Аватарка
        @RequestPart("carPhoto", required = false) carPhoto: MultipartFile?, // Головне фото авто
        
        // Документи
        @RequestPart("techPassportFront", required = false) techPassportFront: MultipartFile?,
        @RequestPart("techPassportBack", required = false) techPassportBack: MultipartFile?,
        @RequestPart("insurancePhoto", required = false) insurancePhoto: MultipartFile?,
        
        // Фото авто (сторони)
        @RequestPart("photoFront", required = false) photoFront: MultipartFile?,
        @RequestPart("photoBack", required = false) photoBack: MultipartFile?,
        @RequestPart("photoLeft", required = false) photoLeft: MultipartFile?,
        @RequestPart("photoRight", required = false) photoRight: MultipartFile?,
        @RequestPart("photoSeatsFront", required = false) photoSeatsFront: MultipartFile?,
        @RequestPart("photoSeatsBack", required = false) photoSeatsBack: MultipartFile?
    ): ResponseEntity<MessageResponse> {
        
        val mapper = jacksonObjectMapper()
        val request = mapper.readValue(requestJson, RegisterDriverRequest::class.java)
        
        val carFiles = collectCarFiles(
            carPhoto, techPassportFront, techPassportBack, insurancePhoto,
            photoFront, photoBack, photoLeft, photoRight, photoSeatsFront, photoSeatsBack
        )

        val response = driverAdminService.createDriver(request, file, carFiles)
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }
    
    // UPDATE
    @PutMapping("/{id}", consumes = ["multipart/form-data"])
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
        
        val carFiles = collectCarFiles(
            carPhoto, techPassportFront, techPassportBack, insurancePhoto,
            photoFront, photoBack, photoLeft, photoRight, photoSeatsFront, photoSeatsBack
        )

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
    fun deleteDriver(@PathVariable id: Long): ResponseEntity<MessageResponse> {
        return ResponseEntity.ok(driverAdminService.deleteDriver(id))
    }

    @PostMapping("/{id}/temp-block")
    fun tempBlockDriver(@PathVariable id: Long, @RequestBody request: TempBlockRequest): ResponseEntity<DriverDto> {
        return ResponseEntity.ok(driverAdminService.blockDriverTemporarily(id, request))
    }
    
    @PatchMapping("/{id}/block")
    fun blockDriverPerm(@PathVariable id: Long): ResponseEntity<DriverDto> {
        return ResponseEntity.ok(driverAdminService.blockDriverPermanently(id))
    }

    @PatchMapping("/{id}/unblock")
    fun unblockDriver(@PathVariable id: Long): ResponseEntity<DriverDto> {
        return ResponseEntity.ok(driverAdminService.unblockDriver(id))
    }

    // --- АКТИВНІСТЬ ---
    @PostMapping("/{id}/activity")
    fun updateActivity(
        @PathVariable id: Long, 
        @RequestBody request: ChangeActivityRequest
    ): ResponseEntity<DriverDto> {
        return ResponseEntity.ok(driverAdminService.updateDriverActivity(id, request.points, request.reason))
    }
}

// DTO для запиту зміни активності
data class ChangeActivityRequest(
    val points: Int,
    val reason: String
)