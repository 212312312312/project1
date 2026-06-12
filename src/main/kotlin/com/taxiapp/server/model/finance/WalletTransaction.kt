package com.taxiapp.server.model.finance

import com.taxiapp.server.model.enums.TransactionType
import com.taxiapp.server.model.user.Driver
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import java.time.LocalDateTime

@Entity
@Table(name = "wallet_transactions")
class WalletTransaction(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_id", nullable = false)
    var driver: Driver,

    @Column(nullable = false)
    var amount: Double, // Может быть отрицательным (списание) или положительным (пополнение)

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var operationType: TransactionType,

    @Column(name = "order_id")
    var orderId: Long? = null, // Ссылка на заказ, если транзакция связана с ним

    @Column(length = 500)
    var description: String? = null,

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()
) {
    @Column(nullable = false, columnDefinition = "double precision default 0.0")
    var balanceAfter: Double = 0.0 // Остаток на кошельке после проведения операции

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(20) default 'COMPLETED'")
    var status: com.taxiapp.server.model.enums.TransactionStatus = com.taxiapp.server.model.enums.TransactionStatus.COMPLETED
}