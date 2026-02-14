package com.taxiapp.server.repository

import com.taxiapp.server.model.order.CancellationReason
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface CancellationReasonRepository : JpaRepository<CancellationReason, Long> {
    fun findAllByIsActiveTrue(): List<CancellationReason>
}