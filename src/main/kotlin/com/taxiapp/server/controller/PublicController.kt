package com.taxiapp.server.controller

import com.taxiapp.server.dto.order.CalculatePriceRequest
import com.taxiapp.server.dto.order.NearbyPlaceSuggestionDto
import com.taxiapp.server.dto.order.ReverseGeocodeResponseDto
import com.taxiapp.server.dto.order.RouteRequestDto
import com.taxiapp.server.dto.order.RouteResponseDto
import com.taxiapp.server.dto.sector.SectorDto
import com.taxiapp.server.dto.tariff.CarTariffDto
import com.taxiapp.server.model.user.Client
import com.taxiapp.server.repository.ClientRepository
import com.taxiapp.server.repository.UserRepository
import com.taxiapp.server.service.GeocodingService
import com.taxiapp.server.service.OrderService
import com.taxiapp.server.service.SectorService
import com.taxiapp.server.service.SettingsService
import com.taxiapp.server.service.TariffAdminService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.security.Principal

@RestController
@RequestMapping("/api/v1/public")
class PublicController(
    private val tariffAdminService: TariffAdminService,
    private val orderService: OrderService,
    private val settingsService: SettingsService,
    private val sectorService: SectorService,
    private val geocodingService: GeocodingService,
    private val clientRepository: ClientRepository,
    private val userRepository: UserRepository
) {

    @GetMapping("/tariffs")
    fun getActiveTariffs(): ResponseEntity<List<CarTariffDto>> {
        return ResponseEntity.ok(tariffAdminService.getAllTariffs())
    }

    @GetMapping("/sectors")
    fun getSectors(): ResponseEntity<List<SectorDto>> {
        return ResponseEntity.ok(sectorService.getAllSectors())
    }

    /**
     * Серверное построение маршрута через OSRM с кэшированием в Redis.
     * Полностью заменяет прямые вызовы платного Google Directions API с клиентов.
     */
    @PostMapping("/route")
    fun calculateRoute(
        @Valid @RequestBody request: RouteRequestDto
    ): ResponseEntity<RouteResponseDto> {
        val route = orderService.fetchRouteFromOsrm(request)
            ?: throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Не вдалося побудувати маршрут")
        return ResponseEntity.ok(route)
    }

    /**
     * Серверный обратный геокодинг (Reverse Geocoding) с кэшем в Redis и секторным фоллбэком.
     * Обеспечивает 100% определение адреса под булавкой на устройствах без GMS (Xiaomi/Huawei).
     */
    @GetMapping("/reverse-geocode")
    fun reverseGeocode(
        @RequestParam("lat") lat: Double,
        @RequestParam("lng") lng: Double,
        @RequestParam(value = "lang", defaultValue = "uk") lang: String
    ): ResponseEntity<ReverseGeocodeResponseDto> {
        val result = geocodingService.reverseGeocode(lat, lng, lang)
        return ResponseEntity.ok(result)
    }

    /**
     * Быстрая выдача 5–6 популярных адресов, важных точек и главных улиц поблизости
     * для пустого экрана ввода адреса (L1 In-Memory + L2 Redis гео-сетка).
     */
    @GetMapping("/nearby-suggestions")
    fun getNearbySuggestions(
        @RequestParam("lat") lat: Double,
        @RequestParam("lng") lng: Double,
        @RequestParam(value = "lang", defaultValue = "uk") lang: String,
        @RequestParam(value = "limit", defaultValue = "6") limit: Int
    ): ResponseEntity<List<NearbyPlaceSuggestionDto>> {
        val result = geocodingService.getNearbySuggestions(lat, lng, lang, limit)
        return ResponseEntity.ok(result)
    }

    @PostMapping("/calculate-price")
    fun calculatePrices(
        @RequestBody request: CalculatePriceRequest,
        principal: Principal?
    ): ResponseEntity<List<CarTariffDto>> {
        val client: Client? = principal?.name?.let { loginOrPhone ->
            clientRepository.findByUserPhone(loginOrPhone).orElseGet {
                userRepository.findByUserLogin(loginOrPhone).orElse(null) as? Client
            }
        }

        val tariffs = orderService.calculatePricesForRoute(
            polyline = request.googleRoutePolyline,
            totalMeters = request.distanceMeters,
            waypointsCount = request.waypointsCount,
            client = client,
            originLat = request.originLat,
            originLng = request.originLng
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