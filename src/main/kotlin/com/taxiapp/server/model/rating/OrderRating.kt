package com.taxiapp.server.model.rating

import com.taxiapp.server.model.order.TaxiOrder
import com.taxiapp.server.model.user.User
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import java.time.LocalDateTime

enum class RatingSource {
    CLIENT, // Клиент оценил водителя
    DRIVER  // Водитель оценил клиента
}

@Entity
@Table(name = "order_ratings")
class OrderRating(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    var order: TaxiOrder,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var source: RatingSource,

    // Того, КОГО оценили (target)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_user_id", nullable = false)
    var targetUser: User,

    @Column(nullable = false)
    var score: Int, // 1-5

    @Column(length = 1000)
    var comment: String? = null,

    @CreationTimestamp
    var createdAt: LocalDateTime = LocalDateTime.now(),

    // Если true - эта оценка исключена из подсчета рейтинга (админом)
    @Column(nullable = false)
    var isIgnored: Boolean = false
)