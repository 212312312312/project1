package com.taxiapp.server.dto.finance

import com.taxiapp.server.model.enums.PayoutStatus
import com.taxiapp.server.model.finance.DriverPayout
import java.time.LocalDateTime

data class DriverPayoutDto(
    val id: Long,
    val driverId: Long,
    val driverName: String,
    val driverPhone: String,
    val orderId: Long?,
    val amount: Double,
    val status: PayoutStatus,
    val comment: String?,
    val createdAt: LocalDateTime,
    val paidAt: LocalDateTime?,
    val paidByDispatcher: String?
) {
    constructor(entity: DriverPayout) : this(
        id = entity.id!!,
        driverId = entity.driver.id!!,
        driverName = entity.driver.fullName.ifBlank { "Водій #${entity.driver.id}" },
        driverPhone = entity.driver.userPhone ?: "",
        orderId = entity.order?.id,
        amount = entity.amount,
        status = entity.status,
        comment = entity.comment,
        createdAt = entity.createdAt,
        paidAt = entity.paidAt,
        paidByDispatcher = entity.paidByDispatcher
    )
}

data class ConfirmPayoutRequest(
    val payoutId: Long,
    val comment: String? = null
)