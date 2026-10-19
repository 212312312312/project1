package com.taxiapp.server.repository

import com.taxiapp.server.model.enums.TransactionType
import com.taxiapp.server.model.finance.WalletTransaction
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface WalletTransactionRepository : JpaRepository<WalletTransaction, Long> {

    // Старый метод (оставляем, чтобы не сломать совместимость, если где-то используется)
    fun findAllByDriverId(driverId: Long): List<WalletTransaction>

    // Явно объявляем метод с пагинацией, чтобы DriverService мог его вызвать
    fun findAllByDriverIdOrderByCreatedAtDesc(driverId: Long, pageable: Pageable): Page<WalletTransaction>

    fun findAllByDriverIdAndStatusOrderByCreatedAtDesc(
        driverId: Long,
        status: com.taxiapp.server.model.enums.TransactionStatus,
        pageable: Pageable
    ): Page<WalletTransaction>

    // Проверка идемпотентности платежа без выгрузки всей таблицы через findAll()
    fun existsByDescriptionContaining(orderId: String): Boolean

    // Подсчет общей суммы для Админки (Финансы)
    @Query("SELECT SUM(ABS(w.amount)) FROM WalletTransaction w WHERE w.operationType = :type")
    fun sumTotalByOperationType(type: TransactionType): Double?
}