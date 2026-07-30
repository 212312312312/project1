package com.taxiapp.server.service

import com.taxiapp.server.dto.classifier.*
import com.taxiapp.server.model.enums.CityGrade
import com.taxiapp.server.model.enums.TariffStatus
import com.taxiapp.server.repository.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CarClassifierService(
    private val cityRepository: CityRepository,
    private val brandRepository: CarBrandRepository,
    private val modelRepository: CarModelRepository,
    private val ruleRepository: CarClassifierRuleRepository
) {

    @Transactional(readOnly = true)
    fun getAllCities(): List<CityDto> {
        return cityRepository.findAllByOrderByNameAsc().map {
            CityDto(id = it.id, name = it.name, grade = it.grade.name)
        }
    }

    @Transactional(readOnly = true)
    fun getAllBrands(): List<CarBrandDto> {
        return brandRepository.findAllByOrderByNameAsc().map {
            CarBrandDto(id = it.id, name = it.name)
        }
    }

    @Transactional(readOnly = true)
    fun getModelsByBrand(brandId: Long): List<CarModelDto> {
        return modelRepository.findByBrandIdOrderByNameAsc(brandId).map {
            CarModelDto(id = it.id, name = it.name)
        }
    }

    @Transactional(readOnly = true)
    fun evaluateCar(request: EvaluateCarRequest): EvaluateCarResponse {
        val rawCity = request.cityName.trim()
        
        // Умный поиск: если передали число (ID) — ищем по ID, иначе по названию
        val city = if (rawCity.toLongOrNull() != null) {
            cityRepository.findById(rawCity.toLong()).orElseGet {
                cityRepository.findByName(rawCity)
            }
        } else {
            cityRepository.findByName(rawCity)
        } ?: throw IllegalArgumentException("Місто '$rawCity' не знайдено в системі")

        val rules = ruleRepository.findMatchingRules(request.modelId, request.year)

        if (rules.isEmpty()) {
            return EvaluateCarResponse(
                cityGrade = city.grade.name,
                maxTariffStatus = TariffStatus.RESTRICTED.name,
                allowedTariffs = emptyList(),
                isAllowed = false
            )
        }

        val status = rules.map {
            if (city.grade == CityGrade.GRADE_A) it.statusGradeA else it.statusGradeB
        }.maxByOrNull { getStatusPriority(it) } ?: TariffStatus.RESTRICTED

        val allowedTariffs = when (status) {
            TariffStatus.BUSINESS -> listOf("STANDARD", "COMFORT", "BUSINESS")
            TariffStatus.COMFORT -> listOf("STANDARD", "COMFORT")
            TariffStatus.STANDARD -> listOf("STANDARD")
            TariffStatus.RESTRICTED -> emptyList()
        }

        return EvaluateCarResponse(
            cityGrade = city.grade.name,
            maxTariffStatus = status.name,
            allowedTariffs = allowedTariffs,
            isAllowed = status != TariffStatus.RESTRICTED
        )
    }

    private fun getStatusPriority(status: TariffStatus): Int {
        return when (status) {
            TariffStatus.BUSINESS -> 4
            TariffStatus.COMFORT -> 3
            TariffStatus.STANDARD -> 2
            TariffStatus.RESTRICTED -> 1
        }
    }
}