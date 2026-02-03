package com.taxiapp.server.controller

import com.taxiapp.server.dto.order.CalculatePriceRequest
import com.taxiapp.server.dto.tariff.CarTariffDto
import com.taxiapp.server.service.OrderService
import com.taxiapp.server.service.SettingsService
import com.taxiapp.server.service.TariffAdminService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/public")
class PublicController(
    private val tariffAdminService: TariffAdminService,
    private val orderService: OrderService,
    private val settingsService: SettingsService
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
        val iconUrl = settingsService.getSettingValue("client_car_icon") ?: ""
        return ResponseEntity.ok(mapOf("url" to iconUrl))
    }

    // --- СПИСКИ ДЛЯ РЕЄСТРАЦІЇ ВОДІЯ ---
    @GetMapping("/info/car-options")
    fun getCarOptions(): ResponseEntity<CarOptionsDto> {
        val makes = listOf(
            CarMakeDto("Toyota", listOf("Camry", "Corolla", "RAV4", "Prius", "Land Cruiser")),
            CarMakeDto("Volkswagen", listOf("Passat", "Golf", "Jetta", "Tiguan", "Touareg")),
            CarMakeDto("Hyundai", listOf("Sonata", "Elantra", "Tucson", "Santa Fe", "Accent")),
            CarMakeDto("Kia", listOf("K5", "Sportage", "Rio", "Sorento", "Ceed")),
            CarMakeDto("Skoda", listOf("Octavia", "Superb", "Fabia", "Kodiaq")),
            CarMakeDto("Renault", listOf("Logan", "Megane", "Duster", "Sandero")),
            CarMakeDto("Ford", listOf("Focus", "Fusion", "Fiesta", "Mondeo")),
            CarMakeDto("Honda", listOf("Civic", "Accord", "CR-V")),
            CarMakeDto("Nissan", listOf("Leaf", "Qashqai", "X-Trail", "Rogue")),
            CarMakeDto("Mercedes-Benz", listOf("E-Class", "C-Class", "S-Class", "Vito")),
            CarMakeDto("BMW", listOf("3 Series", "5 Series", "X5", "X3")),
            CarMakeDto("Audi", listOf("A4", "A6", "Q5", "Q7")),
            CarMakeDto("Chevrolet", listOf("Aveo", "Lacetti", "Cruze", "Bolt"))
        )

        val colors = listOf("Білий", "Чорний", "Сірий", "Сріблястий", "Синій", "Червоний", "Бежевий", "Зелений", "Коричневий", "Жовтий")
        
        val types = listOf("Седан", "Універсал", "Хетчбек", "Кросовер", "Мінівен", "Мікроавтобус")

        return ResponseEntity.ok(CarOptionsDto(makes, colors, types))
    }
}

// DTO класи для цього контролера
data class CarOptionsDto(
    val makes: List<CarMakeDto>,
    val colors: List<String>,
    val types: List<String>
)

data class CarMakeDto(
    val name: String,
    val models: List<String>
)