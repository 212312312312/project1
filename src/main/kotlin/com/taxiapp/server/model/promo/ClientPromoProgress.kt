package com.taxiapp.server.model.promo

import com.taxiapp.server.model.user.Client
import jakarta.persistence.*

@Entity
@Table(name = "client_promo_progress")
data class ClientPromoProgress(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne
    @JoinColumn(name = "client_id", nullable = false)
    val client: Client,

    @ManyToOne
    @JoinColumn(name = "promo_task_id", nullable = false)
    val promoTask: PromoTask,

    @Column(nullable = false)
    var currentRidesCount: Int = 0, // Скільки поїздок зроблено (напр. 2 з 5)

    @Column(nullable = false)
    var isRewardAvailable: Boolean = false // Чи доступна знижка ЗАРАЗ (на наступну поїздку)
)