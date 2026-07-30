package com.taxiapp.server.model.promo

import com.taxiapp.server.model.user.Client
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "client_promo_plan_usages", uniqueConstraints = [
    UniqueConstraint(columnNames = ["client_id", "promo_plan_id"])
])
data class ClientPromoPlanUsage(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    val client: Client,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "promo_plan_id", nullable = false)
    val promoPlan: PromoPlan,

    @Column(name = "used_at", nullable = false)
    val usedAt: LocalDateTime = LocalDateTime.now()
)