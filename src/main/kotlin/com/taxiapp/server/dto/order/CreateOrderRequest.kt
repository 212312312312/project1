package com.taxiapp.server.dto.order

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.time.LocalDateTime

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

    val originLat: Double?,
    val originLng: Double?,
    val destLat: Double?,
    val destLng: Double?,
    val googleRoutePolyline: String?,
    val waypoints: List<WaypointDto>? = null,

    @field:Min(value = 0, message = "Дистанція не може бути негативною")
    val distanceMeters: Int,

    @field:Min(value = 0, message = "Тривалість не може бути негативною")
    val durationSeconds: Int,

    val comment: String? = null,

    val serviceIds: List<Long> = emptyList(),

    val paymentMethod: String = "CASH",

    @field:Min(value = 0, message = "Надбавка не може бути негативною")
    val addedValue: Double = 0.0,

    val scheduledAt: LocalDateTime? = null,

    val districtStart: String? = null,
    val districtEnd: String? = null,
    val deviceId: String? = null,

    @field:Min(value = 0, message = "Сума прискорення не може бути негативною")
    val boostAmount: Double? = 0.0
)