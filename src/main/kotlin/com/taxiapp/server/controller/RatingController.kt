package com.taxiapp.server.controller

import com.taxiapp.server.dto.auth.MessageResponse
import com.taxiapp.server.service.RatingService
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.time.format.DateTimeFormatter

// --- 1. DTO для входящего запроса ---
data class CreateRatingRequest(
    val orderId: Long,
    val score: Int,
    val comment: String?
)

// --- 2. DTO для ответа Админу ---
data class AdminRatingDto(
    val id: Long,
    val score: Int,
    val comment: String?,
    val orderId: Long,
    val driverName: String?,
    val clientName: String?,
    val date: String?
)

@RestController
@RequestMapping("/api/v1")
class RatingController(
    private val ratingService: RatingService
) {

    // 1. Клиент оценивает водителя
    @PostMapping("/client/rate")
    @PreAuthorize("hasAuthority('ROLE_CLIENT')")
    fun rateDriver(@RequestBody request: CreateRatingRequest): ResponseEntity<MessageResponse> {
        ratingService.rateDriver(request.orderId, request.score, request.comment)
        return ResponseEntity.ok(MessageResponse("Дякуємо за оцінку!"))
    }

    // 2. Водитель оценивает клиента
    @PostMapping("/driver/rate")
    @PreAuthorize("hasAuthority('ROLE_DRIVER')")
    fun rateClient(@RequestBody request: CreateRatingRequest): ResponseEntity<MessageResponse> {
        ratingService.rateClient(request.orderId, request.score, request.comment)
        return ResponseEntity.ok(MessageResponse("Оцінка збережена"))
    }

    // 3. Админ: Получить все оценки
    @GetMapping("/admin/ratings")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMINISTRATOR', 'ROLE_DISPATCHER')")
    fun getAllRatings(): ResponseEntity<List<AdminRatingDto>> {
        val ratings = ratingService.getAllRatings()

        val dtos = ratings.map { rating ->
            
            // Безопасное получение имени водителя
            val driverName = try { 
                rating.order.driver?.fullName ?: "Не призначено" 
            } catch (e: Exception) { "---" }
            
            // --- ИСПРАВЛЕНИЕ ЗДЕСЬ ---
            // Было: rating.order.client?.toString()
            // Стало: rating.order.client?.fullName
            val clientInfo = try { 
                 rating.order.client?.fullName ?: rating.order.client?.userPhone ?: "Клієнт"
            } catch (e: Exception) { "Клієнт" }

            // Форматирование даты
            val formattedDate = try {
                rating.createdAt?.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
            } catch (e: Exception) { null }

            AdminRatingDto(
                id = rating.id ?: 0L,
                score = rating.score,
                comment = rating.comment,
                orderId = rating.order.id ?: 0L,
                driverName = driverName,
                clientName = clientInfo, // Теперь тут будет Имя Фамилия
                date = formattedDate
            )
        }

        return ResponseEntity.ok(dtos)
    }

    // 4. Админ: Игнорировать/вернуть оценку
    @PostMapping("/admin/ratings/{id}/ignore")
    @PreAuthorize("hasAuthority('ROLE_ADMINISTRATOR')")
    fun toggleIgnore(@PathVariable id: Long): ResponseEntity<MessageResponse> {
        ratingService.toggleIgnoreRating(id)
        return ResponseEntity.ok(MessageResponse("Статус оцінки змінено"))
    }
}