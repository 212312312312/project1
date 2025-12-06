package com.taxiapp.server.dto.order

import com.taxiapp.server.model.enums.OrderStatus
import com.taxiapp.server.model.order.TaxiOrder
import java.time.LocalDateTime

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
    val tariffName: String,
    
    val originLat: Double?,
    val originLng: Double?,
    val destLat: Double?,
    val destLng: Double?,
    val googleRoutePolyline: String?,

    // !!! НОВІ ПОЛЯ (Додані) !!!
    val stops: List<WaypointDto> = emptyList(),
    val fullRouteDescription: String
) {
    constructor(order: TaxiOrder) : this(
        id = order.id,
        client = OrderClientDto(order.client),
        driver = order.driver?.let { OrderDriverDto(it) },
        status = order.status,
        fromAddress = order.fromAddress,
        toAddress = order.toAddress,
        createdAt = order.createdAt,
        completedAt = order.completedAt,
        price = order.price,
        tariffName = order.tariff.name,
        originLat = order.originLat,
        originLng = order.originLng,
        destLat = order.destLat,
        destLng = order.destLng,
        googleRoutePolyline = order.googleRoutePolyline,

        // 1. Мапимо зупинки з БД у DTO
        stops = order.stops
            .sortedBy { it.stopOrder } // Гарантуємо правильний порядок
            .map { stop ->
                WaypointDto(
                    address = stop.address,
                    lat = stop.lat,
                    lng = stop.lng
                )
            },

        // 2. Формуємо красивий рядок для диспетчера: "А -> Зупинка -> Б"
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
        }
    )
}