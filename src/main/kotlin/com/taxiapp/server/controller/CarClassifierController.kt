package com.taxiapp.server.controller

import com.taxiapp.server.dto.classifier.*
import com.taxiapp.server.service.CarClassifierService
import org.springframework.http.ResponseEntity
import com.taxiapp.server.model.enums.CarBodyType
import com.taxiapp.server.model.enums.CarColor
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

    @GetMapping("/colors")
    fun getColors(): ResponseEntity<List<CarColorDto>> {
        val colors = com.taxiapp.server.model.enums.CarColor.entries.map {
            CarColorDto(key = it.key, name = it.labelUk, hexCode = it.hexCode)
        }
        return ResponseEntity.ok(colors)
    }

    @GetMapping("/body-types")
    fun getBodyTypes(): ResponseEntity<List<CarBodyTypeDto>> {
        val types = com.taxiapp.server.model.enums.CarBodyType.entries.map {
            CarBodyTypeDto(key = it.name, name = it.labelUk)
        }
        return ResponseEntity.ok(types)
    }
}