package com.taxiapp.server.model.promo

import com.taxiapp.server.model.user.Client
import jakarta.persistence.*
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction
import java.time.LocalDateTime

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
    @OnDelete(action = OnDeleteAction.CASCADE)
    val promoTask: PromoTask,

    @Column(nullable = false)
    var currentRidesCount: Int = 0,

    // Поточний прогрес дистанції
    @Column(nullable = false)
    var currentDistanceMeters: Long = 0,

    @Column(nullable = false)
    var isRewardAvailable: Boolean = false,

    // Ваше поле (можно использовать для UI, чтобы скрыть задачу)
    @Column(nullable = false)
    var isFullyCompleted: Boolean = false,

    // --- НОВОЕ ОБЯЗАТЕЛЬНОЕ ПОЛЕ ---
    // Считает количество успешных выполнений. 
    // Нужно для проверки isOneTime в PromoService.
    @Column(name = "completed_count")
    var completedCount: Int = 0,

    // Срок действия награды (сохраняем в БД)
    @Column(name = "reward_expires_at")
    var rewardExpiresAt: LocalDateTime? = null
)