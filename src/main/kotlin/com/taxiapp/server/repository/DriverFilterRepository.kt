package com.taxiapp.server.repository

import com.taxiapp.server.model.filter.DriverFilter
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface DriverFilterRepository : JpaRepository<DriverFilter, Long> {
    fun findAllByDriverId(driverId: Long): List<DriverFilter>
}