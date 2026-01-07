package com.taxiapp.server.controller

import com.taxiapp.server.dto.driver.DriverDto
import com.taxiapp.server.dto.driver.UpdateDriverStatusRequest
import com.taxiapp.server.dto.driver.UpdateLocationRequest
import com.taxiapp.server.repository.DriverRepository
import com.taxiapp.server.service.DriverLocationService
import com.taxiapp.server.service.DriverService
import com.taxiapp.server.security.JwtUtils
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/v1/driver")
class DriverAppController(
    private val driverService: DriverService,
    private val driverLocationService: DriverLocationService,
    private val driverRepository: DriverRepository,
    private val jwtUtils: JwtUtils
) {

    @PatchMapping("/status")
    fun updateStatus(
        @AuthenticationPrincipal userDetails: UserDetails,
        @Valid @RequestBody request: UpdateDriverStatusRequest
    ): ResponseEntity<DriverDto> {

        val driver = driverRepository.findByUserPhone(userDetails.username)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Водитель не найден") }

        if (!driver.isAccountNonLocked) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Ваш акаунт заблокований")
        }

        // Теперь этот метод только меняет флаг isOnline, не затирая координаты
        val driverDto = driverService.updateDriverStatus(driver, request)
        return ResponseEntity.ok(driverDto)
    }

    @PostMapping("/location")
    fun updateLocation(
        @RequestBody request: UpdateLocationRequest,
        servletRequest: HttpServletRequest
    ): ResponseEntity<Void> {
        val token = servletRequest.getHeader("Authorization").substring(7)
        val driverId = jwtUtils.extractUserId(token)
        
        driverLocationService.updateLocation(driverId, request)
        return ResponseEntity.ok().build()
    }

    // --- НОВЫЙ МЕТОД ДЛЯ СВАЙПА (УДАЛЕНИЕ С КАРТЫ) ---
    @DeleteMapping("/location")
    fun logoutFromMap(servletRequest: HttpServletRequest): ResponseEntity<Void> {
        val authHeader = servletRequest.getHeader("Authorization")
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            val token = authHeader.substring(7)
            val driverId = jwtUtils.extractUserId(token)
            
            // Вызываем метод очистки координат в сервисе
            driverLocationService.clearLocation(driverId)
        }
        return ResponseEntity.ok().build()
    }
}