package com.taxiapp.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.taxiapp.server.dto.tariff.CarTariffDto
import com.taxiapp.server.dto.tariff.CreateTariffRequest
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
    private val objectMapper: ObjectMapper
) {

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
            pricePerKmOutCity = tariff.pricePerKmOutCity, // <-- ДОДАНО (Виправлення помилки)
            freeWaitingMinutes = tariff.freeWaitingMinutes,
            pricePerWaitingMinute = tariff.pricePerWaitingMinute,
            isActive = tariff.isActive,
            imageUrl = buildImageUrl(tariff.imageUrl)
        )
    }

    // (Read) Отримати всі тарифи
    @Transactional(readOnly = true)
    fun getAllTariffs(): List<CarTariffDto> {
        return tariffRepository.findAll().map { toDto(it) }
    }

    // (Create) Створити новий тариф
    @Transactional
    fun createTariff(requestJson: String, file: MultipartFile?): CarTariffDto {
        val request = objectMapper.readValue(requestJson, CreateTariffRequest::class.java)
        
        val filename: String? = file?.let {
            fileStorageService.store(it)
        }

        val newTariff = CarTariff(
            name = request.name,
            basePrice = request.basePrice,
            pricePerKm = request.pricePerKm,
            pricePerKmOutCity = request.pricePerKmOutCity, // <-- ДОДАНО: Зберігаємо ціну за містом
            freeWaitingMinutes = request.freeWaitingMinutes,
            pricePerWaitingMinute = request.pricePerWaitingMinute,
            isActive = request.isActive,
            imageUrl = filename
        )
        val savedTariff = tariffRepository.save(newTariff)
        return toDto(savedTariff)
    }

    // (Update) Оновити існуючий тариф
    @Transactional
    fun updateTariff(tariffId: Long, requestJson: String, file: MultipartFile?): CarTariffDto {
        val request = objectMapper.readValue(requestJson, CreateTariffRequest::class.java)
        
        val tariff = tariffRepository.findById(tariffId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Тариф з ID $tariffId не знайдено") }

        var newFilename: String? = tariff.imageUrl

        if (file != null) {
            fileStorageService.delete(tariff.imageUrl)
            newFilename = fileStorageService.store(file)
        }

        tariff.name = request.name
        tariff.basePrice = request.basePrice
        tariff.pricePerKm = request.pricePerKm
        tariff.pricePerKmOutCity = request.pricePerKmOutCity // <-- ДОДАНО: Оновлюємо ціну за містом
        tariff.freeWaitingMinutes = request.freeWaitingMinutes
        tariff.pricePerWaitingMinute = request.pricePerWaitingMinute
        tariff.isActive = request.isActive
        tariff.imageUrl = newFilename

        val updatedTariff = tariffRepository.save(tariff)
        return toDto(updatedTariff)
    }
    
    // (Delete) Видалити тариф
    @Transactional
    fun deleteTariff(tariffId: Long) {
         val tariff = tariffRepository.findById(tariffId)
             .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Тариф з ID $tariffId не знайдено") }
         
         fileStorageService.delete(tariff.imageUrl)
         
         tariffRepository.delete(tariff)
    }

    fun getTariffById(id: Long): CarTariffDto {
        val tariff = tariffRepository.findById(id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Тариф не знайдено") }
        return toDto(tariff) // <-- Використовуємо toDto замість конструктора, щоб не дублювати логіку
    }
}