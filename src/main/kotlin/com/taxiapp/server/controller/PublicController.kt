package com.taxiapp.server.controller

import com.taxiapp.server.dto.order.CalculatePriceRequest
import com.taxiapp.server.dto.tariff.CarTariffDto
import com.taxiapp.server.dto.sector.SectorDto
import com.taxiapp.server.service.OrderService
import com.taxiapp.server.service.SectorService
import com.taxiapp.server.service.SettingsService
import com.taxiapp.server.service.TariffAdminService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import com.taxiapp.server.repository.ClientRepository
import java.security.Principal

@RestController
@RequestMapping("/api/v1/public")
class PublicController(
    private val tariffAdminService: TariffAdminService,
    private val orderService: OrderService,
    private val settingsService: SettingsService,
    private val sectorService: SectorService,
    private val clientRepository: ClientRepository
) {

    @GetMapping("/tariffs")
    fun getActiveTariffs(): ResponseEntity<List<CarTariffDto>> {
        return ResponseEntity.ok(tariffAdminService.getAllTariffs())
    }

    @GetMapping("/sectors")
    fun getSectors(): ResponseEntity<List<SectorDto>> {
        return ResponseEntity.ok(sectorService.getAllSectors())
    }

    @PostMapping("/calculate-price")
    fun calculatePrices(
        @RequestBody request: CalculatePriceRequest,
        principal: Principal?
    ): ResponseEntity<List<CarTariffDto>> {
        val client = principal?.name?.let { username ->
            clientRepository.findByUserPhone(username).orElse(null)
        }

        val tariffs = orderService.calculatePricesForRoute(
            polyline = request.googleRoutePolyline,
            totalMeters = request.distanceMeters,
            waypointsCount = request.waypointsCount,
            client = client
        )
        return ResponseEntity.ok(tariffs)
    }

    @GetMapping("/settings/car-icon")
    fun getCarIconUrl(): ResponseEntity<Map<String, String>> {
        val iconUrl = settingsService.getSettingValue("client_car_icon") ?: ""
        return ResponseEntity.ok(mapOf("url" to iconUrl))
    }

    @GetMapping("/settings/payment-methods")
    fun getPaymentMethodsSettings(): ResponseEntity<Map<String, Boolean>> {
        return ResponseEntity.ok(settingsService.getPaymentSettings())
    }
}