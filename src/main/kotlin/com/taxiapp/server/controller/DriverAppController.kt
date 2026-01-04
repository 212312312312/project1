package com.taxiapp.server.controller

import com.taxiapp.server.dto.driver.DriverDto
import com.taxiapp.server.dto.driver.UpdateDriverStatusRequest
import com.taxiapp.server.dto.driver.UpdateLocationRequest
import com.taxiapp.server.repository.DriverRepository // <-- Новый импорт
import com.taxiapp.server.service.DriverLocationService
import com.taxiapp.server.service.DriverService
import com.taxiapp.server.security.JwtUtils
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails // <-- Новый импорт
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/v1/driver")
// @PreAuthorize("hasRole('DRIVER')") // Пока закомментируй или используй ROLE_DRIVER
class DriverAppController(
    private val driverService: DriverService,
    private val driverLocationService: DriverLocationService,
    private val driverRepository: DriverRepository, // <-- Добавили репозиторий
    private val jwtUtils: JwtUtils
) {

    @PatchMapping("/status")
    fun updateStatus(
        // ИСПРАВЛЕНО: Принимаем UserDetails, а не Driver
        @AuthenticationPrincipal userDetails: UserDetails,
        @Valid @RequestBody request: UpdateDriverStatusRequest
    ): ResponseEntity<DriverDto> {

        // 1. Ищем водителя в базе по телефону из токена
        val driver = driverRepository.findByUserPhone(userDetails.username)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Водитель не найден") }

        // 2. Проверка блокировки
        if (!driver.isAccountNonLocked) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Ваш акаунт заблокований")
        }

        // 3. Обновляем статус
        val driverDto = driverService.updateDriverStatus(driver, request)
        return ResponseEntity.ok(driverDto)
    }

    @PostMapping("/location")
    fun updateLocation(
        @RequestBody request: UpdateLocationRequest,
        servletRequest: HttpServletRequest
    ): ResponseEntity<Void> {
        // Тут мы уже сделали правильно через токен
        val token = servletRequest.getHeader("Authorization").substring(7)
        val driverId = jwtUtils.extractUserId(token)
        
        driverLocationService.updateLocation(driverId, request)
        
        return ResponseEntity.ok().build()
    }
}