package com.taxiapp.server.repository

import com.taxiapp.server.model.filter.DriverFilter
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface DriverFilterRepository : JpaRepository<DriverFilter, Long> {
    fun findAllByDriverId(driverId: Long): List<DriverFilter>

    // Знаходимо фільтри, де Active=True І (Auto=True АБО Cycle=True)
    @Query("SELECT f FROM DriverFilter f JOIN f.driver d WHERE f.isActive = true AND (f.isAuto = true OR f.isCycle = true) AND d.isOnline = true AND d.activityScore > 0")
    fun findAllActiveAutoFilters(): List<DriverFilter>
}