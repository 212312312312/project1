package com.taxiapp.server.repository

import com.taxiapp.server.model.finance.WalletTransaction
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface WalletTransactionRepository : JpaRepository<WalletTransaction, Long> {
    fun findAllByDriverIdOrderByCreatedAtDesc(driverId: Long, pageable: Pageable): Page<WalletTransaction>
}