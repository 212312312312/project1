package com.taxiapp.server.repository

import com.taxiapp.server.model.enums.PayoutStatus
import com.taxiapp.server.model.finance.DriverPayout
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface DriverPayoutRepository : JpaRepository<DriverPayout, Long> {
    fun findAllByStatusOrderByCreatedAtDesc(status: PayoutStatus): List<DriverPayout>
}