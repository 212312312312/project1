package com.taxiapp.server.model.user

import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import java.time.LocalDateTime

@Entity
@Table(name = "driver_cards")
class DriverCard(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_id", nullable = false)
    var driver: Driver,

    @Column(name = "card_number", nullable = false, length = 30)
    var cardNumber: String, // Маскированный вид (Маска от LiqPay)

    @Column(name = "card_token", nullable = false, length = 255)
    var cardToken: String, // ДОБАВЛЕНО: Токен от LiqPay для проведения будущих выплат S2S

    @Column(name = "card_holder", nullable = true)
    var cardHolder: String? = null,

    @Column(name = "is_main", nullable = false)
    var isMain: Boolean = false,

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()
)