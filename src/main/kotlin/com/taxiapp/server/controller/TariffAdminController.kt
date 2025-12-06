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
    private val tariffAdminService: TariffAdminService
) {

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
}