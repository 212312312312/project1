package com.taxiapp.server.dto.finance

import com.taxiapp.server.model.enums.PayoutStatus
import com.taxiapp.server.model.finance.DriverPayout
import java.time.LocalDateTime

data class ConfirmPayoutRequest(
    val payoutId: Long,
    val dispatcherName: String? = null
)

data class CreatePayoutRequest(
    val driverId: Long? = null,
    val orderId: Long? = null,
    val amount: Double,
    val comment: String? = null
)

data class DriverPayoutDto(
    val id: Long?,
    val driverId: Long?,
    val driverName: String?,
    val driverPhone: String?,
    val orderId: Long?,
    val amount: Double,
    val status: PayoutStatus,
    val comment: String?,
    val createdAt: LocalDateTime,
    val paidAt: LocalDateTime?,
    val paidByDispatcher: String?
) {
    constructor(payout: DriverPayout) : this(
        id = payout.id,
        driverId = payout.driver?.id,
        driverName = payout.driver?.fullName ?: (payout.order?.evosDriverCarInfo ?: "Партнер EvoS"),
        driverPhone = payout.driver?.userPhone ?: payout.order?.evosDriverPhone,
        orderId = payout.order?.id,
        amount = payout.amount,
        status = payout.status,
        comment = payout.comment,
        createdAt = payout.createdAt,
        paidAt = payout.paidAt,
        paidByDispatcher = payout.paidByDispatcher
    )
}