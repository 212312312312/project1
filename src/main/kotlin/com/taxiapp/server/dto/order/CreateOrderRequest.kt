package com.taxiapp.server.dto.order

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

data class CreateOrderRequestDto(
    @field:NotBlank(message = "Адреса 'Звідки' не може бути порожньою")
    val fromAddress: String,
    
    @field:NotBlank(message = "Адреса 'Куди' не може бути порожньою")
    val toAddress: String,
    
    @field:NotNull(message = "ID тарифу не може бути null")
    val tariffId: Long,
    
    @field:NotNull(message = "Ціна не може бути null")
    @field:Min(value = 0, message = "Ціна не може бути негативною")
    val price: Double,
    
    // --- НОВІ ПОЛЯ ---
    val originLat: Double?,
    val originLng: Double?,
    val destLat: Double?,
    val destLng: Double?,
    val googleRoutePolyline: String?,
    val waypoints: List<WaypointDto>? = null,
    val distanceMeters: Int,
    val durationSeconds: Int,
    val comment: String? = null,
    
    // ВИПРАВЛЕНО: Залишаємо serviceIds лише ТУТ
    val serviceIds: List<Long> = emptyList(),

    val paymentMethod: String = "CASH",
    // val serviceIds: List<Long> = emptyList(), <--- ЦЕЙ РЯДОК БУВ ЗАЙВИМ (ДУБЛІКАТ)
    val addedValue: Double = 0.0
)