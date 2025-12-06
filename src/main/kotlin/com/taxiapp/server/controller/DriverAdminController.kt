package com.taxiapp.server.controller

import com.taxiapp.server.dto.auth.MessageResponse
import com.taxiapp.server.dto.auth.RegisterDriverRequest
import com.taxiapp.server.dto.driver.DriverDto
import com.taxiapp.server.dto.driver.TempBlockRequest
import com.taxiapp.server.dto.driver.UpdateDriverRequest
import com.taxiapp.server.service.DriverAdminService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/admin/drivers")
// Ми ПРИБРАЛИ @PreAuthorize, щоб уникнути конфліктів. 
// Безпека вже налаштована в SecurityConfig для всіх шляхів /admin/**
class DriverAdminController(
    private val driverAdminService: DriverAdminService
) {

    @GetMapping
    fun getAllDrivers(): ResponseEntity<List<DriverDto>> {
        return ResponseEntity.ok(driverAdminService.getAllDrivers())
    }

    // Метод для карти
    @GetMapping("/online")
    fun getOnlineDrivers(): ResponseEntity<List<DriverDto>> {
        val drivers = driverAdminService.getAllDrivers()
        val onlineDrivers = drivers.filter { it.isOnline }
        return ResponseEntity.ok(onlineDrivers)
    }

    @PostMapping
    fun createDriver(@Valid @RequestBody request: RegisterDriverRequest): ResponseEntity<MessageResponse> {
        val response = driverAdminService.createDriver(request)
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED).body(response)
    }
    
    @PutMapping("/{id}")
    fun updateDriver(@PathVariable id: Long, @Valid @RequestBody request: UpdateDriverRequest): ResponseEntity<DriverDto> {
        return ResponseEntity.ok(driverAdminService.updateDriver(id, request))
    }

    @DeleteMapping("/{id}")
    fun deleteDriver(@PathVariable id: Long): ResponseEntity<MessageResponse> {
        return ResponseEntity.ok(driverAdminService.deleteDriver(id))
    }

    @PostMapping("/{id}/temp-block")
    fun tempBlockDriver(@PathVariable id: Long, @RequestBody request: TempBlockRequest): ResponseEntity<DriverDto> {
        return ResponseEntity.ok(driverAdminService.blockDriverTemporarily(id, request))
    }
    
    // Зверніть увагу: я використовую PatchMapping, переконайтеся, що в React теж Patch, або змініть тут на Post
    @PatchMapping("/{id}/block")
    fun blockDriverPerm(@PathVariable id: Long): ResponseEntity<DriverDto> {
        return ResponseEntity.ok(driverAdminService.blockDriverPermanently(id))
    }

    @PatchMapping("/{id}/unblock")
    fun unblockDriver(@PathVariable id: Long): ResponseEntity<DriverDto> {
        return ResponseEntity.ok(driverAdminService.unblockDriver(id))
    }
}