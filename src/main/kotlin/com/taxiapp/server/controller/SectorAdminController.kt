package com.taxiapp.server.controller

import com.taxiapp.server.dto.auth.MessageResponse
import com.taxiapp.server.dto.sector.CreateSectorRequest
import com.taxiapp.server.dto.sector.SectorDto
import com.taxiapp.server.service.SectorService // Імпортуємо правильний сервіс
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/admin/sectors")
class SectorAdminController(private val sectorService: SectorService) { // Тут теж змінюємо тип на SectorService

    @GetMapping
    fun getList(): ResponseEntity<List<SectorDto>> = ResponseEntity.ok(sectorService.getAllSectors())

    @PostMapping
    fun create(@RequestBody request: CreateSectorRequest): ResponseEntity<SectorDto> = 
        ResponseEntity.ok(sectorService.createSector(request))

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: Long): ResponseEntity<MessageResponse> {
        sectorService.deleteSector(id)
        return ResponseEntity.ok(MessageResponse("Сектор видалено"))
    }
}