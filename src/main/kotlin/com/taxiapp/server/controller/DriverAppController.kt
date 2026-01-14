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
import java.security.Principal // Додано про всяк випадок, хоча ми використаємо UserDetails

@RestController
@RequestMapping("/api/v1/driver")
class DriverAppController(
    private val driverService: DriverService,
    private val driverLocationService: DriverLocationService,
    private val driverRepository: DriverRepository,
    private val jwtUtils: JwtUtils
) {

    /**
     * Оновлення статусу (ОНЛАЙН/ОФЛАЙН)
     */
    @PatchMapping("/status")
    fun updateStatus(
        @AuthenticationPrincipal userDetails: UserDetails,
        @Valid @RequestBody request: UpdateDriverStatusRequest
    ): ResponseEntity<DriverDto> {

        // Отримуємо ім'я (логін або телефон) з токена
        val username = userDetails.username

        // Шукаємо водія (спочатку за логіном, потім за телефоном)
        val driver = (driverRepository.findByUserLogin(username) ?: driverRepository.findByUserPhone(username))
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Водій не знайдений")

        // Перевірка на блокування
        if (!driver.isAccountNonLocked) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Ваш акаунт заблокований")
        }

        // Оновлюємо тільки статус, зберігаючи координати в базі
        val driverDto = driverService.updateDriverStatus(driver, request)
        return ResponseEntity.ok(driverDto)
    }

    /**
     * Оновлення поточної геолокації водія
     */
    @PostMapping("/location")
    fun updateLocation(
        @RequestBody request: UpdateLocationRequest,
        servletRequest: HttpServletRequest
    ): ResponseEntity<Void> {
        val authHeader = servletRequest.getHeader("Authorization")
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            val token = authHeader.substring(7)
            val driverId = jwtUtils.extractUserId(token)
            
            driverLocationService.updateLocation(driverId, request)
        }
        return ResponseEntity.ok().build()
    }

    /**
     * Отримання профілю водія (Виправлено)
     */
    @GetMapping("/me")
    fun getDriverProfile(@AuthenticationPrincipal userDetails: UserDetails): ResponseEntity<DriverDto> {
        val username = userDetails.username
        
        // Використовуємо той самий надійний пошук, що і в updateStatus
        // (тому що username може бути як телефоном, так і логіном)
        val driver = (driverRepository.findByUserLogin(username) ?: driverRepository.findByUserPhone(username))
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Водія не знайдено")

        return ResponseEntity.ok(DriverDto(driver))
    }

    /**
     * Видалення водія з карти (свайп додатку або вихід)
     */
    @DeleteMapping("/location")
    fun logoutFromMap(servletRequest: HttpServletRequest): ResponseEntity<Void> {
        val authHeader = servletRequest.getHeader("Authorization")
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            val token = authHeader.substring(7)
            val driverId = jwtUtils.extractUserId(token)
            
            // Очищаємо координати в базі, щоб водій зник з карти диспетчера
            driverLocationService.clearLocation(driverId)
        }
        return ResponseEntity.ok().build()
    }
    
}