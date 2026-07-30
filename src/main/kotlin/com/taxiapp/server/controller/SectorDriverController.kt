package com.taxiapp.server.controller

import com.taxiapp.server.dto.sector.SectorDto
import com.taxiapp.server.service.SectorService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/driver/sectors")
class SectorDriverController(private val sectorService: SectorService) {

    @GetMapping
    fun getAllSectors(): ResponseEntity<List<SectorDto>> {
        return ResponseEntity.ok(sectorService.getAllSectors())
    }
}