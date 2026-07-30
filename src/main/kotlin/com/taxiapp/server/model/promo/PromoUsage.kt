package com.taxiapp.server.model.promo

import com.taxiapp.server.model.user.Client
import jakarta.persistence.*
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction
import java.time.LocalDateTime

@Entity
@Table(name = "promo_usages")
data class PromoUsage(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    val client: Client,

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "promo_code_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    val promoCode: PromoCode,

    @Column(nullable = false)
    var isUsed: Boolean = false,

    // --- ВИПРАВЛЕНО: activatedAt -> createdAt ---
    // Це виправить помилку в PromoService, де ми передаємо createdAt
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    
    @Column(name = "expires_at")
    val expiresAt: LocalDateTime? = null,

    @Column(name = "used_at")
    var usedAt: LocalDateTime? = null
)