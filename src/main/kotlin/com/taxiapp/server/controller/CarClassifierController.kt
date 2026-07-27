package com.taxiapp.server.controller

import com.taxiapp.server.dto.classifier.*
import com.taxiapp.server.service.CarClassifierService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/public/classifier")
@CrossOrigin(originPatterns = ["*"]) // <--- ТУТ ЗМІНЕНО origins НА originPatterns
class CarClassifierController(
    private val classifierService: CarClassifierService
) {

    @GetMapping("/cities")
    fun getCities(): ResponseEntity<List<CityDto>> {
        return ResponseEntity.ok(classifierService.getAllCities())
    }

    @GetMapping("/brands")
    fun getBrands(): ResponseEntity<List<CarBrandDto>> {
        return ResponseEntity.ok(classifierService.getAllBrands())
    }

    @GetMapping("/brands/{brandId}/models")
    fun getModels(@PathVariable brandId: Long): ResponseEntity<List<CarModelDto>> {
        return ResponseEntity.ok(classifierService.getModelsByBrand(brandId))
    }

    @PostMapping("/evaluate")
    fun evaluate(@RequestBody request: EvaluateCarRequest): ResponseEntity<EvaluateCarResponse> {
        return ResponseEntity.ok(classifierService.evaluateCar(request))
    }
}