package com.taxiapp.server.repository

import com.taxiapp.server.model.enums.TransactionType
import com.taxiapp.server.model.finance.WalletTransaction
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
interface WalletTransactionRepository : JpaRepository<WalletTransaction, Long> {

    fun findAllByDriverId(driverId: Long): List<WalletTransaction>

    fun findAllByDriverIdOrderByCreatedAtDesc(driverId: Long, pageable: Pageable): Page<WalletTransaction>

    fun findAllByDriverIdAndStatusOrderByCreatedAtDesc(
        driverId: Long,
        status: com.taxiapp.server.model.enums.TransactionStatus,
        pageable: Pageable
    ): Page<WalletTransaction>

    fun existsByDescriptionContaining(orderId: String): Boolean

    // Подсчет общей суммы для Админки (Финансы и Аналитика)
    @Query("SELECT SUM(ABS(w.amount)) FROM WalletTransaction w WHERE w.operationType = :type")
    fun sumTotalByOperationType(@Param("type") type: TransactionType): Double?

    // Подсчет суммы комиссий / бонусов за выбранный период
    @Query("""
        SELECT COALESCE(SUM(ABS(w.amount)), 0.0)
        FROM WalletTransaction w
        WHERE w.operationType = :type
          AND w.createdAt BETWEEN :startDate AND :endDate
    """)
    fun sumTotalByOperationTypeAndPeriod(
        @Param("type") type: TransactionType,
        @Param("startDate") startDate: LocalDateTime,
        @Param("endDate") endDate: LocalDateTime
    ): Double?
}