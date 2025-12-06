package com.taxiapp.server.controller

import com.taxiapp.server.dto.driver.DriverDto
import com.taxiapp.server.dto.driver.UpdateDriverStatusRequest
import com.taxiapp.server.model.user.Driver
import com.taxiapp.server.service.DriverService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException // <-- НОВИЙ ІМПОРТ
import org.springframework.http.HttpStatus // <-- НОВИЙ ІМПОРТ

@RestController
@RequestMapping("/driver")
@PreAuthorize("hasRole('DRIVER')")
class DriverAppController(
    private val driverService: DriverService
) {
    
    @PatchMapping("/status")
    fun updateStatus(
        @AuthenticationPrincipal driver: Driver,
        @Valid @RequestBody request: UpdateDriverStatusRequest
    ): ResponseEntity<DriverDto> {
        
        // --- ВИПРАВЛЕННЯ ТУТ ---
        // Додаємо перевірку, чи не заблокований водій
        // (isAccountNonLocked() перевіряє і постійне, і тимчасове блокування)
        if (!driver.isAccountNonLocked) {
             throw ResponseStatusException(HttpStatus.FORBIDDEN, "Ваш акаунт заблокований")
        }
        // ---
        
        val driverDto = driverService.updateDriverStatus(driver, request)
        return ResponseEntity.ok(driverDto)
    }
}