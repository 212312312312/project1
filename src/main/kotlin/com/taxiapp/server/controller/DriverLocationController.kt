package com.taxiapp.server.controller

import com.taxiapp.server.dto.driver.DriverLocationDto
import com.taxiapp.server.service.DriverLocationService
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/admin/drivers/location-map")
class DriverLocationController(
    private val driverLocationService: DriverLocationService
) {

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMINISTRATOR', 'DISPATCHER')")
    fun getOnlineDriversForMap(): ResponseEntity<List<DriverLocationDto>> {
        return ResponseEntity.ok(driverLocationService.getOnlineDriversForMap())
    }
}