package com.taxiapp.server.dto.order

import com.taxiapp.server.dto.order.WaypointDto
import com.taxiapp.server.dto.service.TaxiServiceDto // <-- Імпортуємо ваш DTO
import com.taxiapp.server.model.enums.OrderStatus
import com.taxiapp.server.model.order.TaxiOrder
import java.time.LocalDateTime

// data class OrderServiceDto(...) <-- ВИДАЛИВ, бо ми використовуємо TaxiServiceDto

data class TaxiOrderDto(
    val id: Long,
    val client: OrderClientDto,
    val driver: OrderDriverDto?,
    val status: OrderStatus,
    val fromAddress: String,
    val toAddress: String,
    val createdAt: LocalDateTime,
    val completedAt: LocalDateTime?,
    val price: Double,
    
    // Поля статистики
    val distanceMeters: Int?,
    val durationSeconds: Int?,
    val tariffName: String?,

    val originLat: Double?,
    val originLng: Double?,
    val destLat: Double?,
    val destLng: Double?,
    val googleRoutePolyline: String?,

    val stops: List<WaypointDto> = emptyList(),
    val fullRouteDescription: String,

    val formattedWaypoints: String,
    val comment: String? = null,
    
    val paymentMethod: String,
    
    // Нове поле
    val addedValue: Double,
    
    // Використовуємо TaxiServiceDto
    val services: List<TaxiServiceDto> = emptyList()
) {
    // Вторинний конструктор
    constructor(order: TaxiOrder) : this(
        id = order.id ?: 0L,
        client = OrderClientDto(order.client),
        driver = order.driver?.let { OrderDriverDto(it) },
        status = order.status,
        fromAddress = order.fromAddress,
        toAddress = order.toAddress,
        createdAt = order.createdAt,
        completedAt = order.completedAt,
        price = order.price ?: 0.0,
        
        distanceMeters = order.distanceMeters,
        durationSeconds = order.durationSeconds,
        
        // Беремо ім'я тарифу безпечно
        tariffName = order.tariffName ?: order.tariff.name,

        originLat = order.originLat,
        originLng = order.originLng,
        destLat = order.destLat,
        destLng = order.destLng,
        googleRoutePolyline = order.googleRoutePolyline,

        stops = order.stops
            .sortedBy { it.stopOrder }
            .map { stop ->
                WaypointDto(
                    address = stop.address,
                    lat = stop.lat,
                    lng = stop.lng,
                    stopOrder = stop.stopOrder // <-- ВАЖЛИВО: Додав це поле
                )
            },

        fullRouteDescription = buildString {
            append(order.fromAddress)
            if (order.stops.isNotEmpty()) {
                order.stops.sortedBy { it.stopOrder }.forEach { stop ->
                    append(" ➔ ")
                    append(stop.address)
                }
            }
            append(" ➔ ")
            append(order.toAddress)
        },

        formattedWaypoints = if (order.stops.isEmpty()) {
            ""
        } else {
            val sortedStops = order.stops.sortedBy { it.stopOrder }
            if (sortedStops.size == 1) {
                sortedStops[0].address
            } else {
                sortedStops.mapIndexed { index, stop -> 
                    "(${index + 1}) ${stop.address}" 
                }.joinToString("-")
            }
        },
        
        comment = order.comment,

        // Передаємо нові поля
        paymentMethod = order.paymentMethod ?: "CASH",
        addedValue = order.addedValue,

        // Мапимо послуги у TaxiServiceDto
        services = order.selectedServices.map { service ->
            TaxiServiceDto(
                id = service.id ?: 0L,
                name = service.name,
                price = service.price
            )
        }
    )
}