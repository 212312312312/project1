package com.taxiapp.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.taxiapp.server.dto.tariff.CarTariffDto
import com.taxiapp.server.dto.tariff.CreateTariffRequest
import com.taxiapp.server.model.order.CarTariff
import com.taxiapp.server.repository.AppSettingRepository
import com.taxiapp.server.repository.CarTariffRepository
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException

@Service
class TariffAdminService(
    private val tariffRepository: CarTariffRepository,
    private val fileStorageService: FileStorageService,
    private val objectMapper: ObjectMapper,
    private val redisTemplate: RedisTemplate<String, Any>,
    private val appSettingRepository: AppSettingRepository
) {
    private val CACHE_KEY = "tariffs:all"

    // 🔥 ИСПРАВЛЕНО: Теперь вызываем buildFullUrl из FileStorageService
    private fun buildImageUrl(filename: String?): String? {
        return fileStorageService.buildFullUrl(filename)
    }
    
    // Конвертация в DTO
    private fun toDto(tariff: CarTariff): CarTariffDto {
        return CarTariffDto(
            id = tariff.id,
            name = tariff.name,
            basePrice = tariff.basePrice,
            pricePerKm = tariff.pricePerKm,
            sortOrder = tariff.sortOrder,
            pricePerKmOutCity = tariff.pricePerKmOutCity,
            extraWaypointPrice = tariff.extraWaypointPrice,
            freeWaitingMinutes = tariff.freeWaitingMinutes,
            pricePerWaitingMinute = tariff.pricePerWaitingMinute,
            isActive = tariff.isActive,
            isBeta = tariff.isBeta,
            isUnavailable = tariff.isUnavailable,
            imageUrl = buildImageUrl(tariff.imageUrl)
        )
    }
    
    @Transactional
    fun reorderTariff(id: Long, direction: String): List<CarTariffDto> {
        val tariffs = tariffRepository.findAllByIsActiveTrueOrderBySortOrderAsc()
        val currentIndex = tariffs.indexOfFirst { it.id == id }
        
        if (currentIndex == -1) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Тариф не знайдено")
        }

        val targetIndex = if (direction.uppercase() == "UP") currentIndex - 1 else currentIndex + 1

        if (targetIndex in tariffs.indices) {
            val currentTariff = tariffs[currentIndex]
            val targetTariff = tariffs[targetIndex]

            if (currentTariff.sortOrder == targetTariff.sortOrder) {
                tariffs.forEachIndexed { index, t ->
                    t.sortOrder = index
                }
            }

            val tempOrder = currentTariff.sortOrder
            currentTariff.sortOrder = targetTariff.sortOrder
            targetTariff.sortOrder = tempOrder

            tariffRepository.saveAll(tariffs)
            redisTemplate.delete(CACHE_KEY)
        }
        
        return tariffRepository.findAllByIsActiveTrueOrderBySortOrderAsc().map { toDto(it) }
    }

    @Transactional(readOnly = true)
    fun getAllTariffs(): List<CarTariffDto> {
        // Если в кэше старые данные без http/https — сбрасываем кэш
        val cached = redisTemplate.opsForValue().get(CACHE_KEY) as? List<*>
        if (cached != null) {
            val list = cached.map { objectMapper.convertValue(it, CarTariffDto::class.java) }
            val hasOldUrls = list.any { it.imageUrl != null && !it.imageUrl!!.startsWith("http") }
            if (!hasOldUrls) {
                return list
            }
            redisTemplate.delete(CACHE_KEY) // Сбрасываем устаревший кэш
        }

        val tariffs = tariffRepository.findAllByIsActiveTrueOrderBySortOrderAsc().map { toDto(it) }
        redisTemplate.opsForValue().set(CACHE_KEY, tariffs)
        return tariffs
    }

    @Transactional
    fun createTariff(requestJson: String, file: MultipartFile?): CarTariffDto {
        val request = objectMapper.readValue(requestJson, CreateTariffRequest::class.java)
        
        val filename: String? = file?.let {
            fileStorageService.storeFile(it)
        }

        val newTariff = CarTariff(
            name = request.name,
            basePrice = request.basePrice,
            pricePerKm = request.pricePerKm,
            pricePerKmOutCity = request.pricePerKmOutCity,
            freeWaitingMinutes = request.freeWaitingMinutes,
            pricePerWaitingMinute = request.pricePerWaitingMinute,
            extraWaypointPrice = request.extraWaypointPrice,
            isActive = request.isActive,
            isBeta = request.isBeta,
            isUnavailable = request.isUnavailable,
            imageUrl = filename,
            sortOrder = tariffRepository.findAll().size
        )
        val savedTariff = tariffRepository.save(newTariff)
        
        redisTemplate.delete(CACHE_KEY)
        return toDto(savedTariff)
    }

    @Transactional
    fun updateTariff(tariffId: Long, requestJson: String, file: MultipartFile?): CarTariffDto {
        val request = objectMapper.readValue(requestJson, CreateTariffRequest::class.java)
        
        val tariff = tariffRepository.findById(tariffId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Тариф з ID $tariffId не знайдено") }

        var newFilename: String? = tariff.imageUrl

        if (file != null) {
            fileStorageService.delete(tariff.imageUrl)
            newFilename = fileStorageService.storeFile(file)
        }

        tariff.name = request.name
        tariff.basePrice = request.basePrice
        tariff.pricePerKm = request.pricePerKm
        tariff.pricePerKmOutCity = request.pricePerKmOutCity
        tariff.extraWaypointPrice = request.extraWaypointPrice
        tariff.freeWaitingMinutes = request.freeWaitingMinutes
        tariff.pricePerWaitingMinute = request.pricePerWaitingMinute
        tariff.isActive = request.isActive
        tariff.isBeta = request.isBeta
        tariff.isUnavailable = request.isUnavailable
        tariff.imageUrl = newFilename

        val updatedTariff = tariffRepository.save(tariff)
        
        redisTemplate.delete(CACHE_KEY)
        return toDto(updatedTariff)
    }
    
    @Transactional
    fun deleteTariff(id: Long) {
        val tariff = tariffRepository.findById(id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Тариф з ID $id не знайдено") }
        
        tariff.isActive = false
        tariffRepository.save(tariff)
        
        redisTemplate.delete(CACHE_KEY)
    }

    fun getTariffById(id: Long): CarTariffDto {
        val tariff = tariffRepository.findById(id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Тариф не знайдено") }
        return toDto(tariff) 
    }

    @Transactional(readOnly = true)
    fun getMinOrderDistance(): Double {
        val setting = appSettingRepository.findById("min_order_distance").orElse(null)
        return setting?.value?.toDoubleOrNull() ?: 3.0
    }

    @Transactional
    fun updateMinOrderDistance(distance: Double) {
        val setting = appSettingRepository.findById("min_order_distance")
            .orElse(com.taxiapp.server.model.setting.AppSetting(key = "min_order_distance", value = "3.0"))
        setting.value = distance.toString()
        appSettingRepository.save(setting)
        
        redisTemplate.delete(CACHE_KEY)
    }
}