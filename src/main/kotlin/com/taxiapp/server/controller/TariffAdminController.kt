package com.taxiapp.server.controller

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.taxiapp.server.dto.auth.MessageResponse
import com.taxiapp.server.dto.tariff.CarTariffDto
import com.taxiapp.server.dto.tariff.CreateTariffRequest
import com.taxiapp.server.dto.tariff.UpdateTariffRequest
import com.taxiapp.server.service.TariffAdminService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/v1/admin/tariffs")
// @PreAuthorize ПРИБРАНО! (Безпека в SecurityConfig)
class TariffAdminController(
    private val tariffAdminService: TariffAdminService,
    private val timeSurgeRuleRepository: com.taxiapp.server.repository.TimeSurgeRuleRepository,
    private val weatherSurgeRuleRepository: com.taxiapp.server.repository.WeatherSurgeRuleRepository,
    private val openMeteoWeatherService: com.taxiapp.server.service.OpenMeteoWeatherService
) {

    @GetMapping("/surge")
    fun getSurgeConfig(): ResponseEntity<com.taxiapp.server.dto.surge.SurgeConfigDto> {
        val timeRules = timeSurgeRuleRepository.findAll().map {
            com.taxiapp.server.dto.surge.TimeSurgeRuleDto(
                id = it.id,
                name = it.name,
                daysOfWeek = it.daysOfWeek,
                startTime = it.startTime.toString().take(5),
                endTime = it.endTime.toString().take(5),
                multiplier = it.multiplier,
                isActive = it.isActive
            )
        }

        // Авто-ініціалізація базових погодних правил за відсутності
        if (weatherSurgeRuleRepository.count() == 0L) {
            weatherSurgeRuleRepository.saveAll(
                listOf(
                    com.taxiapp.server.model.surge.WeatherSurgeRule(weatherType = "RAIN", name = "Дощ", multiplier = 1.15, isActive = true),
                    com.taxiapp.server.model.surge.WeatherSurgeRule(weatherType = "HEAVY_RAIN", name = "Злива", multiplier = 1.25, isActive = true),
                    com.taxiapp.server.model.surge.WeatherSurgeRule(weatherType = "SNOW", name = "Снігопад", multiplier = 1.30, isActive = true),
                    com.taxiapp.server.model.surge.WeatherSurgeRule(weatherType = "THUNDERSTORM", name = "Гроза", multiplier = 1.35, isActive = true),
                    com.taxiapp.server.model.surge.WeatherSurgeRule(weatherType = "FOG", name = "Туман", multiplier = 1.10, isActive = true)
                )
            )
        }

        val weatherRules = weatherSurgeRuleRepository.findAll().map {
            com.taxiapp.server.dto.surge.WeatherSurgeRuleDto(
                id = it.id,
                weatherType = it.weatherType,
                name = it.name,
                multiplier = it.multiplier,
                isActive = it.isActive
            )
        }

        val currentWeather = openMeteoWeatherService.getCurrentWeather()

        return ResponseEntity.ok(
            com.taxiapp.server.dto.surge.SurgeConfigDto(
                weatherSurgeEnabled = openMeteoWeatherService.isWeatherSurgeEnabled(),
                timeRules = timeRules,
                weatherRules = weatherRules,
                currentWeather = currentWeather
            )
        )
    }

    @PostMapping("/surge/time-rules")
    fun saveTimeRule(@RequestBody dto: com.taxiapp.server.dto.surge.TimeSurgeRuleDto): ResponseEntity<MessageResponse> {
        val rule = if (dto.id != null && dto.id > 0) {
            timeSurgeRuleRepository.findById(dto.id).orElseThrow().apply {
                name = dto.name
                daysOfWeek = dto.daysOfWeek.toMutableSet()
                startTime = java.time.LocalTime.parse(dto.startTime)
                endTime = java.time.LocalTime.parse(dto.endTime)
                multiplier = dto.multiplier
                isActive = dto.isActive
            }
        } else {
            com.taxiapp.server.model.surge.TimeSurgeRule(
                name = dto.name,
                daysOfWeek = dto.daysOfWeek.toMutableSet(),
                startTime = java.time.LocalTime.parse(dto.startTime),
                endTime = java.time.LocalTime.parse(dto.endTime),
                multiplier = dto.multiplier,
                isActive = dto.isActive
            )
        }
        timeSurgeRuleRepository.save(rule)
        return ResponseEntity.ok(MessageResponse("Правило часу збережено"))
    }

    @DeleteMapping("/surge/time-rules/{id}")
    fun deleteTimeRule(@PathVariable id: Long): ResponseEntity<MessageResponse> {
        timeSurgeRuleRepository.deleteById(id)
        return ResponseEntity.ok(MessageResponse("Правило часу видалено"))
    }

    @PutMapping("/surge/time-rules/{id}/toggle")
    fun toggleTimeRule(@PathVariable id: Long): ResponseEntity<MessageResponse> {
        val rule = timeSurgeRuleRepository.findById(id).orElseThrow()
        rule.isActive = !rule.isActive
        timeSurgeRuleRepository.save(rule)
        return ResponseEntity.ok(MessageResponse("Статус правила змінено"))
    }

    @PutMapping("/surge/weather-toggle")
    fun toggleWeatherSurge(@RequestParam enabled: Boolean): ResponseEntity<MessageResponse> {
        openMeteoWeatherService.setWeatherSurgeEnabled(enabled)
        return ResponseEntity.ok(MessageResponse("Погодний коефіцієнт оновлено"))
    }

    @PutMapping("/surge/weather-rules/{id}")
    fun updateWeatherRule(
        @PathVariable id: Long,
        @RequestParam multiplier: Double,
        @RequestParam isActive: Boolean
    ): ResponseEntity<MessageResponse> {
        val rule = weatherSurgeRuleRepository.findById(id).orElseThrow()
        rule.multiplier = multiplier
        rule.isActive = isActive
        weatherSurgeRuleRepository.save(rule)
        return ResponseEntity.ok(MessageResponse("Погодне правило оновлено"))
    }

    @GetMapping("/surge/live-weather")
    fun getLiveWeather(
        @RequestParam(required = false) lat: Double?,
        @RequestParam(required = false) lng: Double?
    ): ResponseEntity<com.taxiapp.server.dto.surge.WeatherStatusDto> {
        return ResponseEntity.ok(openMeteoWeatherService.getCurrentWeather(lat, lng))
    }
    
    @PostMapping("/{id}/reorder")
    fun reorderTariff(
        @PathVariable id: Long,
        @RequestParam direction: String // "UP" или "DOWN"
    ): ResponseEntity<List<CarTariffDto>> {
        val updatedTariffs = tariffAdminService.reorderTariff(id, direction)
        return ResponseEntity.ok(updatedTariffs)
    }
    @GetMapping
    fun getAllTariffs(): ResponseEntity<List<CarTariffDto>> {
        return ResponseEntity.ok(tariffAdminService.getAllTariffs())
    }

    @GetMapping("/{id}")
    fun getTariff(@PathVariable id: Long): ResponseEntity<CarTariffDto> {
        return ResponseEntity.ok(tariffAdminService.getTariffById(id))
    }

    // (Create)
    @PostMapping(consumes = ["multipart/form-data"])
    fun createTariff(
        @RequestPart("request") requestJson: String,
        @RequestPart("file", required = false) file: MultipartFile?
    ): ResponseEntity<CarTariffDto> {
        
        // Парсимо JSON вручну
        val mapper = jacksonObjectMapper()
        val request = mapper.readValue(requestJson, CreateTariffRequest::class.java)
        
        // Викликаємо сервіс (він має приймати String JSON, як у вас було раніше, або переробіть його)
        // Я залишаю виклик, який ми узгодили раніше:
        val tariff = tariffAdminService.createTariff(requestJson, file)
        
        return ResponseEntity.status(HttpStatus.CREATED).body(tariff)
    }

    // (Update)
    @PutMapping("/{id}", consumes = ["multipart/form-data"])
    fun updateTariff(
        @PathVariable id: Long,
        @RequestPart("request") requestJson: String,
        @RequestPart("file", required = false) file: MultipartFile?
    ): ResponseEntity<CarTariffDto> {
        val tariff = tariffAdminService.updateTariff(id, requestJson, file)
        return ResponseEntity.ok(tariff)
    }

    @DeleteMapping("/{id}")
    fun deleteTariff(@PathVariable id: Long): ResponseEntity<MessageResponse> {
        tariffAdminService.deleteTariff(id)
        return ResponseEntity.ok(MessageResponse("Тариф успішно видалено"))
    }

    @GetMapping("/min-distance")
    fun getMinDistance(): ResponseEntity<Map<String, Double>> {
        val distance = tariffAdminService.getMinOrderDistance()
        return ResponseEntity.ok(mapOf("minDistance" to distance))
    }

    @GetMapping("/evos-tariffs")
    fun getEvoSTariffs(): ResponseEntity<List<String>> {
        return ResponseEntity.ok(tariffAdminService.getEvoSTariffs())
    }

    @PutMapping("/min-distance")
    fun updateMinDistance(@RequestParam distance: Double): ResponseEntity<MessageResponse> {
        tariffAdminService.updateMinOrderDistance(distance)
        return ResponseEntity.ok(MessageResponse("Мінімальний кілометраж успішно оновлено"))
    }
}