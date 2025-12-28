package com.taxiapp.server.controller

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.taxiapp.server.dto.auth.MessageResponse
import com.taxiapp.server.dto.auth.RegisterDriverRequest
import com.taxiapp.server.dto.driver.DriverDto
import com.taxiapp.server.dto.driver.TempBlockRequest
import com.taxiapp.server.dto.driver.UpdateDriverRequest
import com.taxiapp.server.service.DriverAdminService
import jakarta.validation.Valid
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

    @GetMapping("/online")
    fun getOnlineDrivers(): ResponseEntity<List<DriverDto>> {
        val drivers = driverAdminService.getAllDrivers()
        val onlineDrivers = drivers.filter { it.isOnline }
        return ResponseEntity.ok(onlineDrivers)
    }

    // CREATE (с логами)
    @PostMapping(consumes = ["multipart/form-data"])
    fun createDriver(
        @RequestPart("request") requestJson: String,
        @RequestPart("file", required = false) file: MultipartFile?
    ): ResponseEntity<MessageResponse> {
        
        println(">>> CONTROLLER: Create Driver Request Received")
        println(">>> JSON: $requestJson")
        if (file != null) {
            println(">>> FILE: ${file.originalFilename}, Size: ${file.size} bytes")
        } else {
            println(">>> FILE IS NULL")
        }

        val mapper = jacksonObjectMapper()
        val request = mapper.readValue(requestJson, RegisterDriverRequest::class.java)
        
        val response = driverAdminService.createDriver(request, file)
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }
    
    // UPDATE (с логами)
    @PutMapping("/{id}", consumes = ["multipart/form-data"])
    fun updateDriver(
        @PathVariable id: Long, 
        @RequestPart("request") requestJson: String,
        @RequestPart("file", required = false) file: MultipartFile?
    ): ResponseEntity<DriverDto> {
        
        println(">>> CONTROLLER: Update Driver Request ($id)")
        if (file != null) {
            println(">>> FILE: ${file.originalFilename}")
        }

        val mapper = jacksonObjectMapper()
        val request = mapper.readValue(requestJson, UpdateDriverRequest::class.java)
        
        return ResponseEntity.ok(driverAdminService.updateDriver(id, request, file))
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
}