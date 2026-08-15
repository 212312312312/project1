package com.taxiapp.server.dto.evos

import com.fasterxml.jackson.annotation.JsonProperty

// --- СОЗДАНИЕ И РАСЧЕТ ЗАКАЗА ---
data class EvoSRoutePointDto(
    @JsonProperty("name") val name: String,
    @JsonProperty("number") val number: String? = null,
    @JsonProperty("lat") val lat: Double? = null,
    @JsonProperty("lng") val lng: Double? = null
)

data class EvoSCreateOrderRequestDto(
    @JsonProperty("user_full_name") val userFullName: String,
    @JsonProperty("user_phone") val userPhone: String,
    @JsonProperty("client_sub_card") val clientSubCard: String? = null,
    @JsonProperty("required_time") val requiredTime: String? = null, // ISO дата подачи
    @JsonProperty("reservation") val reservation: Boolean = false,    // true для заказов на время
    @JsonProperty("route_address_entrance_from") val routeAddressEntranceFrom: String? = null,
    @JsonProperty("comment") val comment: String? = null,
    @JsonProperty("add_cost") val addCost: Double = 0.0,
    @JsonProperty("order_cost") val orderCost: Double? = null,
    @JsonProperty("wagon") val wagon: Boolean = false,
    @JsonProperty("minibus") val minibus: Boolean = false,
    @JsonProperty("premium") val premium: Boolean = false,
    @JsonProperty("flexible_tariff_name") val flexibleTariffName: String? = null,
    @JsonProperty("baggage") val baggage: Boolean = false,
    @JsonProperty("animal") val animal: Boolean = false,
    @JsonProperty("conditioner") val conditioner: Boolean = false,
    @JsonProperty("courier_delivery") val courierDelivery: Boolean = false,
    @JsonProperty("route_undefined") val routeUndefined: Boolean = false,
    @JsonProperty("terminal") val terminal: Boolean = false,
    @JsonProperty("receipt") val receipt: Boolean = false,
    @JsonProperty("extra_charge_codes") val extraChargeCodes: List<String>? = null,
    @JsonProperty("route") val route: List<EvoSRoutePointDto>,
    @JsonProperty("taxiColumnId") val taxiColumnId: Int = 0,
    @JsonProperty("payment_type") val paymentType: Int? = 0 // 0 - Cash, 1 - Card
)

data class EvoSCreateOrderResponseDto(
    @JsonProperty("dispatching_order_uid") val dispatchingOrderUid: String,
    @JsonProperty("find_car_timeout") val findCarTimeout: Int? = 120,
    @JsonProperty("find_car_delay") val findCarDelay: Int? = 0
)

// --- СОСТОЯНИЕ ЗАКАЗА ---
data class EvoSDriverCarPositionDto(
    @JsonProperty("lat") val lat: Double?,
    @JsonProperty("lng") val lng: Double?,
    @JsonProperty("time_positioned_utc") val timePositionedUtc: String?,
    @JsonProperty("bearing") val bearing: Float? = 0f,
    @JsonProperty("speed") val speed: Int? = 0,
    @JsonProperty("status") val status: String?
)

// --- ОТВЕТ НА РАСЧЕТ СТОИМОСТИ (POST /api/weborders/cost) ---
data class EvoSCalculateCostResponseDto(
    @JsonProperty("order_cost") val orderCost: String? = null,
    @JsonProperty("currency") val currency: String? = null,
    @JsonProperty("discount_trip") val discountTrip: Boolean? = false,
    @JsonProperty("can_pay_bonuses") val canPayBonuses: Boolean? = false
)

// --- ЗАПРОС ДОБАВОЧНОЙ СТОИМОСТИ (PUT /api/weborders/{uid}/cost/additional) ---
data class EvoSAddCostRequestDto(
    @JsonProperty("add_cost") val addCost: Double
)

data class EvoSOrderStateResponseDto(
    @JsonProperty("dispatching_order_uid") val dispatchingOrderUid: String,
    @JsonProperty("order_cost") val orderCost: String?,
    @JsonProperty("currency") val currency: String?,
    @JsonProperty("order_car_info") val orderCarInfo: String?,
    @JsonProperty("driver_phone") val driverPhone: String?,
    @JsonProperty("required_time") val requiredTime: String?, // <-- ДОБАВЛЕНО
    @JsonProperty("close_reason") val closeReason: Int?,
    @JsonProperty("cancel_reason_comment") val cancelReasonComment: String?, // <-- ДОБАВЛЕНО
    @JsonProperty("execution_status") val executionStatus: String?,
    @JsonProperty("driver_execution_status") val driverExecutionStatus: Int?,
    @JsonProperty("order_is_archive") val orderIsArchive: Boolean?,
    @JsonProperty("drivercar_position") val drivercarPosition: EvoSDriverCarPositionDto?,
    @JsonProperty("crew_average_rating") val crewAverageRating: Double?, // <-- ДОБАВЛЕНО
    @JsonProperty("rating") val rating: Int?, // <-- ДОБАВЛЕНО
    @JsonProperty("rating_comment") val ratingComment: String? // <-- ДОБАВЛЕНО
)

// --- ОТМЕНА ЗАКАЗА ---
data class EvoSCancelResponseDto(
    @JsonProperty("dispatching_order_uid") val dispatchingOrderUid: String,
    @JsonProperty("order_client_cancel_result") val orderClientCancelResult: Int
)