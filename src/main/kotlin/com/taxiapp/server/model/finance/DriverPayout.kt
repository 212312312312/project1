package com.taxiapp.server.model.finance

import com.taxiapp.server.model.enums.PayoutStatus
import com.taxiapp.server.model.order.TaxiOrder
import com.taxiapp.server.model.user.Driver
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "driver_payouts")
class DriverPayout(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_id", nullable = true)
    val driver: Driver? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = true)
    val order: TaxiOrder? = null,

    @Column(nullable = false)
    val amount: Double,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: PayoutStatus = PayoutStatus.PENDING,

    @Column(nullable = true)
    val comment: String? = null,

    @Column(nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(nullable = true)
    var paidAt: LocalDateTime? = null,

    @Column(nullable = true)
    var paidByDispatcher: String? = null
)