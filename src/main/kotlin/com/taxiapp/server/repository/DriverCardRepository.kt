package com.taxiapp.server.repository

import com.taxiapp.server.model.user.DriverCard
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface DriverCardRepository : JpaRepository<DriverCard, Long> {
    fun findAllByDriverId(driverId: Long): List<DriverCard>
}