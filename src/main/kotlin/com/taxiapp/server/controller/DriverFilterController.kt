package com.taxiapp.server.controller

import com.taxiapp.server.dto.auth.MessageResponse
import com.taxiapp.server.dto.filter.CreateFilterRequest
import com.taxiapp.server.dto.filter.DriverFilterDto
import com.taxiapp.server.service.DriverFilterService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/driver/filters")
class DriverFilterController(private val filterService: DriverFilterService) {

    @GetMapping
    fun getFilters(): ResponseEntity<List<DriverFilterDto>> = ResponseEntity.ok(filterService.getMyFilters())

    @PostMapping
    fun create(@RequestBody req: CreateFilterRequest): ResponseEntity<DriverFilterDto> = 
        ResponseEntity.ok(filterService.createFilter(req))

    @PatchMapping("/{id}/toggle")
    fun toggle(@PathVariable id: Long): ResponseEntity<Void> {
        filterService.toggleFilter(id)
        return ResponseEntity.ok().build()
    }

    @PatchMapping("/disable-all")
    fun disableAll(): ResponseEntity<Void> {
        filterService.disableAll()
        return ResponseEntity.ok().build()
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: Long): ResponseEntity<MessageResponse> {
        filterService.deleteFilter(id)
        return ResponseEntity.ok(MessageResponse("Фільтр видалено"))
    }
}