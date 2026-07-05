package com.taxiapp.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.taxiapp.server.dto.tariff.CarTariffDto
import com.taxiapp.server.dto.tariff.CreateTariffRequest
import org.springframework.data.redis.core.RedisTemplate
import com.taxiapp.server.model.order.CarTariff
import com.taxiapp.server.repository.CarTariffRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.support.ServletUriComponentsBuilder

@Service
class TariffAdminService(
    private val tariffRepository: CarTariffRepository,
    private val fileStorageService: FileStorageService,
    private val objectMapper: ObjectMapper,
    private val redisTemplate: RedisTemplate<String, Any>
) {
    private val CACHE_KEY = "tariffs:all"

    private fun buildImageUrl(filename: String?): String? {
        if (filename.isNullOrBlank()) return null
        return "/images/$filename"
    }
    
    // Вспомогательная функция для конвертации DTO
    private fun toDto(tariff: CarTariff): CarTariffDto {
        return CarTariffDto(
            id = tariff.id,
            name = tariff.name,
            basePrice = tariff.basePrice,
            pricePerKm = tariff.pricePerKm,
            sortOrder = tariff.sortOrder, // 👈 ИСПРАВЛЕНО: Передаем поле в DTO
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
        // Получаем текущий список
        val tariffs = tariffRepository.findAllByIsActiveTrueOrderBySortOrderAsc()
        val currentIndex = tariffs.indexOfFirst { it.id == id }
        
        if (currentIndex == -1) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Тариф не знайдено")
        }

        val targetIndex = if (direction.uppercase() == "UP") currentIndex - 1 else currentIndex + 1

        if (targetIndex in tariffs.indices) {
            val currentTariff = tariffs[currentIndex]
            val targetTariff = tariffs[targetIndex]

            // 🔥 ФИКС: Если индексы совпали (например, после миграции все равны 0),
            // автоматически переиндексируем весь список по порядку (0, 1, 2, 3...)
            if (currentTariff.sortOrder == targetTariff.sortOrder) {
                tariffs.forEachIndexed { index, t ->
                    t.sortOrder = index
                }
            }

            // Теперь значения гарантированно разные — спокойно поочередно меняем их местами
            val tempOrder = currentTariff.sortOrder
            currentTariff.sortOrder = targetTariff.sortOrder
            targetTariff.sortOrder = tempOrder

            // Сохраняем весь список тарифов с обновленной индексацией
            tariffRepository.saveAll(tariffs)
            
            redisTemplate.delete(CACHE_KEY) // Чистим кэш Redis
        }
        
        return tariffRepository.findAllByIsActiveTrueOrderBySortOrderAsc().map { toDto(it) }
    }

    // (Read) Отримати всі тарифи
    @Transactional(readOnly = true)
    fun getAllTariffs(): List<CarTariffDto> {
        val cached = redisTemplate.opsForValue().get(CACHE_KEY) as? List<*>
        if (cached != null) {
            return cached.map { objectMapper.convertValue(it, CarTariffDto::class.java) }
        }

        // 👈 ИСПРАВЛЕНО: Используем метод с сортировкой, чтобы в приложении тоже был верный порядок
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
            sortOrder = tariffRepository.findAll().size // Авто-выставление порядка в конец списка
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
}