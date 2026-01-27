package com.taxiapp.server.repository

import com.taxiapp.server.model.rating.OrderRating
import com.taxiapp.server.model.rating.RatingSource
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface OrderRatingRepository : JpaRepository<OrderRating, Long> {
    
    // Найти последние оценки для пользователя, которые не игнорируются
    fun findByTargetUserIdAndSourceAndIsIgnoredFalseOrderByCreatedAtDesc(
        targetUserId: Long, 
        source: RatingSource, 
        pageable: Pageable
    ): List<OrderRating>

    // Для админки: найти все оценки
    fun findAllByOrderByCreatedAtDesc(): List<OrderRating>
}