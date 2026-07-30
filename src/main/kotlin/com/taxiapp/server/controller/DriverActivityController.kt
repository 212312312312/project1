package com.taxiapp.server.controller

import com.taxiapp.server.dto.driver.DriverActivityDto
import com.taxiapp.server.repository.DriverRepository
import com.taxiapp.server.service.DriverActivityService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/v1/driver/activity")
class DriverActivityController(
    private val driverActivityService: DriverActivityService,
    private val driverRepository: DriverRepository
) {

    @GetMapping
    fun getActivity(@AuthenticationPrincipal userDetails: UserDetails): ResponseEntity<DriverActivityDto> {
        val username = userDetails.username
        val driver = (driverRepository.findByUserLogin(username) ?: driverRepository.findByUserPhone(username))
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Водій не знайдений")

        val activityData = driverActivityService.getDriverActivity(driver)
        return ResponseEntity.ok(activityData)
    }
}