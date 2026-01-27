package com.taxiapp.server.controller

import com.taxiapp.server.dto.order.CalculatePriceRequest
import com.taxiapp.server.dto.tariff.CarTariffDto
import com.taxiapp.server.service.OrderService
import com.taxiapp.server.service.SettingsService // <-- Импорт
import com.taxiapp.server.service.TariffAdminService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/public")
class PublicController(
    private val tariffAdminService: TariffAdminService,
    private val orderService: OrderService,
    private val settingsService: SettingsService // <-- ДОБАВИЛИ СЕРВИС
) {

    @GetMapping("/tariffs")
    fun getActiveTariffs(): ResponseEntity<List<CarTariffDto>> {
        return ResponseEntity.ok(tariffAdminService.getAllTariffs())
    }

    @PostMapping("/calculate-price")
    fun calculatePrice(@RequestBody request: CalculatePriceRequest): List<CarTariffDto> {
        return orderService.calculatePricesForRoute(request.googleRoutePolyline, request.distanceMeters)
    }

    @GetMapping("/settings/car-icon")
    fun getCarIconUrl(): ResponseEntity<Map<String, String>> {
        // --- ИСПРАВЛЕНИЕ: Ключ должен совпадать с тем, что в React (client_car_icon) ---
        val iconUrl = settingsService.getSettingValue("client_car_icon") ?: ""
        // -------------------------------------------------------------------------------
        
        return ResponseEntity.ok(mapOf("url" to iconUrl))
    }
}