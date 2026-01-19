package com.taxiapp.server.controller

import com.taxiapp.server.dto.order.CalculatePriceRequest
import com.taxiapp.server.dto.order.CalculatedTariffDto
import com.taxiapp.server.dto.tariff.CarTariffDto
import com.taxiapp.server.service.OrderService // <-- Добавили импорт сервиса
import com.taxiapp.server.service.TariffAdminService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping // <-- Добавили импорт
import org.springframework.web.bind.annotation.RequestBody // <-- Добавили импорт
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/public") // Убедись, что путь совпадает с тем, куда стучится клиент (в клиенте было /public, здесь /api/v1/public. Проверь ApiService в Android)
class PublicController(
    private val tariffAdminService: TariffAdminService,
    private val orderService: OrderService // <-- Внедрили OrderService
) {

    @GetMapping("/tariffs")
    fun getActiveTariffs(): ResponseEntity<List<CarTariffDto>> {
        return ResponseEntity.ok(tariffAdminService.getAllTariffs())
    }

    @PostMapping("/calculate-price")
    fun calculatePrice(@RequestBody request: CalculatePriceRequest): List<CalculatedTariffDto> {
        return orderService.calculatePricesForRoute(request.googleRoutePolyline, request.distanceMeters)
    }
}