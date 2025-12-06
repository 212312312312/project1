package com.taxiapp.server.controller

import com.taxiapp.server.dto.driver.DriverLocationDto
import com.taxiapp.server.service.DriverLocationService
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/admin/drivers")
// --- ИСПРАВЛЕНИЕ ЗДЕСЬ ---
@PreAuthorize("hasAnyRole('ADMINISTRATOR', 'DISPATCHER')") // Было 'DISPATCHT'
class DriverLocationController(
    private val driverLocationService: DriverLocationService
) {

    // "Карта водителей" (Real-time update 5 сек)
    @GetMapping("/online")
    fun getOnlineDriversForMap(): ResponseEntity<List<DriverLocationDto>> {
        return ResponseEntity.ok(driverLocationService.getOnlineDriversForMap())
    }
}