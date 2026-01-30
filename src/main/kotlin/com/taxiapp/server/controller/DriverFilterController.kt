package com.taxiapp.server.controller

import com.taxiapp.server.dto.filter.CreateFilterRequest
import com.taxiapp.server.dto.filter.DriverFilterDto
// ДОДАНО ІМПОРТ:
import com.taxiapp.server.dto.filter.UpdateFilterModeRequest 
import com.taxiapp.server.service.DriverFilterService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/driver/filters")
class DriverFilterController(
    private val filterService: DriverFilterService
) {

    @GetMapping
    fun getMyFilters(): List<DriverFilterDto> {
        return filterService.getMyFilters()
    }

    @PostMapping
    fun createFilter(@RequestBody req: CreateFilterRequest): DriverFilterDto {
        return filterService.createFilter(req)
    }

    @PatchMapping("/{id}/toggle")
    fun toggleFilter(@PathVariable id: Long) {
        filterService.toggleFilter(id)
    }

    // --- НОВЫЙ МЕТОД: Обновление режимов (Авто, Эфир, Цикл) ---
    @PatchMapping("/{id}/mode")
    fun updateFilterMode(
        @PathVariable id: Long,
        @RequestBody req: UpdateFilterModeRequest
    ): DriverFilterDto {
        return filterService.updateFilterMode(id, req)
    }

    // --- НОВЫЙ МЕТОД: Полное редактирование фильтра ---
    @PutMapping("/{id}")
    fun updateFilter(
        @PathVariable id: Long,
        @RequestBody req: CreateFilterRequest
    ): DriverFilterDto {
        return filterService.updateFilter(id, req)
    }

    @PatchMapping("/disable-all")
    fun disableAllFilters() {
        filterService.disableAll()
    }

    @DeleteMapping("/{id}")
    fun deleteFilter(@PathVariable id: Long) {
        filterService.deleteFilter(id)
    }
}