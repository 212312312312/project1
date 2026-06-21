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
    private val redisTemplate: RedisTemplate<String, Any> // <-- Инжектим Redis
) {
    private val CACHE_KEY = "tariffs:all" // Ключ кэша в Redis

    // Вспомогательная функция для построения полного URL
    private fun buildImageUrl(filename: String?): String? {
        if (filename.isNullOrBlank()) return null
        return ServletUriComponentsBuilder.fromCurrentContextPath()
            .path("/images/")
            .path(filename)
            .toUriString()
    }
    
    // Вспомогательная функция для конвертации DTO
    private fun toDto(tariff: CarTariff): CarTariffDto {
        return CarTariffDto(
            id = tariff.id,
            name = tariff.name,
            basePrice = tariff.basePrice,
            pricePerKm = tariff.pricePerKm,
            pricePerKmOutCity = tariff.pricePerKmOutCity,
            extraWaypointPrice = tariff.extraWaypointPrice,
            freeWaitingMinutes = tariff.freeWaitingMinutes,
            pricePerWaitingMinute = tariff.pricePerWaitingMinute,
            isActive = tariff.isActive,
            isBeta = tariff.isBeta,               // <--- ДОБАВЛЕНО
            isUnavailable = tariff.isUnavailable,
            imageUrl = buildImageUrl(tariff.imageUrl)
        )
    }

    // (Read) Отримати всі тарифи — ТЕПЕР ИЗ КЭША!
    @Transactional(readOnly = true)
    fun getAllTariffs(): List<CarTariffDto> {
        // Проверяем наличие в Redis
        val cached = redisTemplate.opsForValue().get(CACHE_KEY) as? List<*>
        if (cached != null) {
            // Безопасно приводим типы через objectMapper, защищаясь от ошибок каста Jackson
            return cached.map { objectMapper.convertValue(it, CarTariffDto::class.java) }
        }

        // Если в кэше пусто — берем из БД и сохраняем в Redis
        val tariffs = tariffRepository.findAll().map { toDto(it) }
        redisTemplate.opsForValue().set(CACHE_KEY, tariffs)
        return tariffs
    }

    // (Create) Створити новий тариф — Сбрасываем кэш
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
            imageUrl = filename
        )
        val savedTariff = tariffRepository.save(newTariff)
        
        redisTemplate.delete(CACHE_KEY) // <-- Инвалидация кэша
        return toDto(savedTariff)
    }

    // (Update) Оновити існуючий тариф — Сбрасываем кэш
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
        
        redisTemplate.delete(CACHE_KEY) // <-- Инвалидация кэша
        return toDto(updatedTariff)
    }
    
    // (Delete) Видалити тариф — Сбрасываем кэш
    @Transactional
    fun deleteTariff(tariffId: Long) {
         val tariff = tariffRepository.findById(tariffId)
             .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Тариф з ID $tariffId не знайдено") }
         
         fileStorageService.delete(tariff.imageUrl)
         
         tariffRepository.delete(tariff)
         
         redisTemplate.delete(CACHE_KEY) // <-- Инвалидация кэша
    }

    fun getTariffById(id: Long): CarTariffDto {
        val tariff = tariffRepository.findById(id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Тариф не знайдено") }
        return toDto(tariff) 
    }
}