package com.taxiapp.server.dto.order

import com.taxiapp.server.dto.service.TaxiServiceDto
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

    val startedAt: LocalDateTime? = null,
    val waitingPrice: Double = 0.0,
    val freeWaitingMinutes: Int = 3,
    val pricePerWaitingMinute: Double = 0.0,
    
    val distanceMeters: Int?,
    val durationSeconds: Int?,
    val tariffName: String?,

    val originLat: Double?,
    val originLng: Double?,
    val destLat: Double?,
    val destLng: Double?,
    val googleRoutePolyline: String?,

    val arrivedAt: LocalDateTime? = null,
    val scheduledAt: LocalDateTime?,
    val carModel: String? = null,
    val carPlate: String? = null,
    val carColor: String? = null,

    val stops: List<WaypointDto> = emptyList(),
    val fullRouteDescription: String,
    val formattedWaypoints: String,
    val comment: String? = null,
    val paymentMethod: String,
    val addedValue: Double,
    val services: List<TaxiServiceDto> = emptyList(),

    val fromSector: String? = null,
    val toSector: String? = null,

    // --- НОВЕ ПОЛЕ ---
    // Передаємо статус підтвердження на клієнт (Web/Android)
    val isDriverConfirmed: Boolean
) {
    constructor(order: TaxiOrder) : this(
        id = order.id ?: 0L,
        client = OrderClientDto(order.client),
        driver = order.driver?.let { OrderDriverDto(it) },
        status = order.status,
        fromAddress = order.fromAddress,
        toAddress = order.toAddress,
        createdAt = order.createdAt,
        completedAt = order.completedAt,
        price = order.price,

        startedAt = order.startedAt,
        waitingPrice = order.waitingPrice,
        freeWaitingMinutes = order.tariff.freeWaitingMinutes,
        pricePerWaitingMinute = order.tariff.pricePerWaitingMinute,
        
        distanceMeters = order.distanceMeters,
        durationSeconds = order.durationSeconds,
        tariffName = order.tariffName ?: order.tariff.name,

        originLat = order.originLat,
        originLng = order.originLng,
        destLat = order.destLat,
        destLng = order.destLng,
        googleRoutePolyline = order.googleRoutePolyline,

        arrivedAt = order.arrivedAt,
        scheduledAt = order.scheduledAt,
        carModel = order.driver?.car?.let { "${it.make} ${it.model}" },
        carPlate = order.driver?.car?.plateNumber,
        carColor = order.driver?.car?.color, 

        stops = order.stops
            .sortedBy { it.stopOrder }
            .map { stop ->
                WaypointDto(
                    address = stop.address,
                    lat = stop.lat,
                    lng = stop.lng,
                    stopOrder = stop.stopOrder
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
        paymentMethod = order.paymentMethod ?: "CASH",
        addedValue = order.addedValue,

        services = order.selectedServices.map { service ->
            TaxiServiceDto(
                id = service.id ?: 0L,
                name = service.name,
                price = service.price
            )
        },

        toSector = order.destinationSector?.name,
        fromSector = order.originSector?.name,

        // --- Ініціалізація нового поля ---
        // Якщо в базі null -> вважаємо false
        isDriverConfirmed = order.isDriverConfirmed ?: false
    )
}