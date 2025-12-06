package com.taxiapp.server.controller

import com.taxiapp.server.dto.tariff.CarTariffDto
import com.taxiapp.server.service.TariffAdminService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/public")
class PublicController(
    private val tariffAdminService: TariffAdminService
) {

    @GetMapping("/tariffs")
    fun getActiveTariffs(): ResponseEntity<List<CarTariffDto>> {
        // Получаем все тарифы (в идеале - только активные, но пока можно все)
        return ResponseEntity.ok(tariffAdminService.getAllTariffs())
    }
}