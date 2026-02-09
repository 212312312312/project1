package com.taxiapp.server.controller

import com.taxiapp.server.dto.auth.MessageResponse
import com.taxiapp.server.dto.driver.DriverSearchSettingsDto
import com.taxiapp.server.dto.driver.DriverSearchStateDto
import com.taxiapp.server.dto.driver.UpdateDisabilityRequest
import com.taxiapp.server.model.user.Driver
import com.taxiapp.server.repository.DriverRepository
import com.taxiapp.server.service.DriverService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/v1/driver")
class DriverSettingsController(
    private val driverService: DriverService,
    private val driverRepository: DriverRepository
) {

    // --- НАЛАШТУВАННЯ ПОШУКУ (Search Settings) ---

    @GetMapping("/search-settings")
    fun getSettings(@AuthenticationPrincipal userDetails: UserDetails): ResponseEntity<DriverSearchStateDto> {
        val driver = getDriver(userDetails)
        // Используем метод из DriverService
        return ResponseEntity.ok(driverService.getSearchState(driver))
    }

    @PostMapping("/search-settings")
    fun updateSettings(
        @AuthenticationPrincipal userDetails: UserDetails,
        @RequestBody settings: DriverSearchSettingsDto
    ): ResponseEntity<DriverSearchStateDto> {
        val driver = getDriver(userDetails)
        try {
            // Используем метод из DriverService
            val updatedState = driverService.updateSearchSettings(driver, settings)
            return ResponseEntity.ok(updatedState)
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message)
        }
    }

    // --- МЕДИЧНІ ДАНІ ---
    @PatchMapping("/profile/medical")
    fun updateDisabilityStatus(
        @AuthenticationPrincipal userDetails: UserDetails,
        @RequestBody request: UpdateDisabilityRequest
    ): ResponseEntity<MessageResponse> {
        val driver = getDriver(userDetails)
        driverService.updateDisabilityStatus(driver.id, request)
        return ResponseEntity.ok(MessageResponse("Медичні дані оновлено"))
    }

    private fun getDriver(userDetails: UserDetails): Driver {
        val username = userDetails.username
        return (driverRepository.findByUserLogin(username) ?: driverRepository.findByUserPhone(username))
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Водія не знайдено")
    }
}