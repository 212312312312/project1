package com.taxiapp.server.repository

import com.taxiapp.server.model.order.TaxiOrderTrack
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface TaxiOrderTrackRepository : JpaRepository<TaxiOrderTrack, Long> {
    fun findByOrderIdOrderByTimestampAsc(orderId: Long): List<TaxiOrderTrack>
}