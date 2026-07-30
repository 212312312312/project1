package com.taxiapp.server.model.finance

import com.taxiapp.server.model.user.Driver
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import java.time.LocalDateTime

enum class PaymentStatus { PENDING, SUCCESS, FAILED }

@Entity
@Table(name = "payment_transactions")
class PaymentTransaction(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_id", nullable = false)
    var driver: Driver,

    @Column(nullable = false)
    var amount: Double,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: PaymentStatus = PaymentStatus.PENDING,

    @Column(unique = true)
    var externalReference: String? = null, // ID платежа в LiqPay (потом)

    @CreationTimestamp
    var createdAt: LocalDateTime = LocalDateTime.now(),
    
    var finishedAt: LocalDateTime? = null
)