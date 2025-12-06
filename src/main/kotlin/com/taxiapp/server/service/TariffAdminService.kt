package com.taxiapp.server.service

import com.fasterxml.jackson.databind.ObjectMapper // <-- НОВЫЙ ИМПОРТ
import com.taxiapp.server.dto.tariff.CarTariffDto
import com.taxiapp.server.dto.tariff.CreateTariffRequest
import com.taxiapp.server.model.order.CarTariff
import com.taxiapp.server.repository.CarTariffRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile // <-- НОВЫЙ ИМПОРТ
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.support.ServletUriComponentsBuilder // <-- НОВЫЙ ИМПОРТ

@Service
class TariffAdminService(
    private val tariffRepository: CarTariffRepository,
    private val fileStorageService: FileStorageService, // <-- НОВАЯ ЗАВИСИМОСТЬ
    private val objectMapper: ObjectMapper // <-- НОВАЯ ЗАВИСИМОСТЬ (для JSON)
) {

    // Вспомогательная функция для построения полного URL
    private fun buildImageUrl(filename: String?): String? {
        if (filename.isNullOrBlank()) return null
        // Создает URL вида http://localhost:8080/images/filename.png
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
            freeWaitingMinutes = tariff.freeWaitingMinutes,
            pricePerWaitingMinute = tariff.pricePerWaitingMinute,
            isActive = tariff.isActive,
            imageUrl = buildImageUrl(tariff.imageUrl) // <-- Строим URL
        )
    }

    // (Read) Отримати всі тарифи
    @Transactional(readOnly = true)
    fun getAllTariffs(): List<CarTariffDto> {
        return tariffRepository.findAll().map { toDto(it) } // <-- Используем toDto
    }

    // (Create) Створити новий тариф
    @Transactional
    fun createTariff(requestJson: String, file: MultipartFile?): CarTariffDto {
        // 1. Конвертируем JSON-строку в DTO
        val request = objectMapper.readValue(requestJson, CreateTariffRequest::class.java)
        
        // 2. Сохраняем файл (если он есть)
        val filename: String? = file?.let {
            fileStorageService.store(it)
        }

        // 3. Сохраняем тариф в БД
        val newTariff = CarTariff(
            name = request.name,
            basePrice = request.basePrice,
            pricePerKm = request.pricePerKm,
            freeWaitingMinutes = request.freeWaitingMinutes,
            pricePerWaitingMinute = request.pricePerWaitingMinute,
            isActive = request.isActive,
            imageUrl = filename // Сохраняем имя файла
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

        var newFilename: String? = tariff.imageUrl // Сохраняем старое имя

        // 2. Если пришел новый файл (file != null)
        if (file != null) {
            // 2a. Удаляем старый файл
            fileStorageService.delete(tariff.imageUrl)
            // 2b. Сохраняем новый
            newFilename = fileStorageService.store(file)
        }

        // 3. Обновляем тариф
        tariff.name = request.name
        tariff.basePrice = request.basePrice
        tariff.pricePerKm = request.pricePerKm
        tariff.freeWaitingMinutes = request.freeWaitingMinutes
        tariff.pricePerWaitingMinute = request.pricePerWaitingMinute
        tariff.isActive = request.isActive
        tariff.imageUrl = newFilename // Устанавливаем новое (или старое) имя

        val updatedTariff = tariffRepository.save(tariff)
        return toDto(updatedTariff)
    }
    
    // (Delete) Видалити тариф
    @Transactional
    fun deleteTariff(tariffId: Long) {
         val tariff = tariffRepository.findById(tariffId)
             .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Тариф з ID $tariffId не знайдено") }
         
         // Удаляем связанный файл
         fileStorageService.delete(tariff.imageUrl)
         
         tariffRepository.delete(tariff)
    }

    fun getTariffById(id: Long): CarTariffDto {
        val tariff = tariffRepository.findById(id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Тариф не знайдено") }
        return CarTariffDto(tariff)
    }
}