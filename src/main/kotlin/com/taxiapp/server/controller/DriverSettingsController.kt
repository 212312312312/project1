package com.taxiapp.server.controller

import com.taxiapp.server.dto.driver.DriverSearchSettingsDto
import com.taxiapp.server.dto.driver.DriverSearchStateDto
import com.taxiapp.server.model.user.Driver
import com.taxiapp.server.repository.DriverRepository
import com.taxiapp.server.repository.UserRepository
import com.taxiapp.server.service.DriverService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/v1/driver/search-settings")
class DriverSettingsController(
    private val driverService: DriverService,
    private val driverRepository: DriverRepository
) {

    @GetMapping
    fun getSettings(@AuthenticationPrincipal userDetails: UserDetails): ResponseEntity<DriverSearchStateDto> {
        val driver = getDriver(userDetails)
        return ResponseEntity.ok(driverService.getSearchState(driver))
    }

    @PostMapping
    fun updateSettings(
        @AuthenticationPrincipal userDetails: UserDetails,
        @RequestBody settings: DriverSearchSettingsDto
    ): ResponseEntity<DriverSearchStateDto> {
        val driver = getDriver(userDetails)
        try {
            val updatedState = driverService.updateSearchSettings(driver, settings)
            return ResponseEntity.ok(updatedState)
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message)
        }
    }

    private fun getDriver(userDetails: UserDetails): Driver {
        val username = userDetails.username
        return (driverRepository.findByUserLogin(username) ?: driverRepository.findByUserPhone(username))
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Водія не знайдено")
    }
}