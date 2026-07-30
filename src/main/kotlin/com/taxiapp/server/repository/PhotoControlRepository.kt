package com.taxiapp.server.repository

import com.taxiapp.server.model.driver.PhotoControl
import com.taxiapp.server.model.enums.PhotoControlStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
interface PhotoControlRepository : JpaRepository<PhotoControl, Long> {
    
    fun findByDriverIdAndStatusIn(
        driverId: Long, 
        statuses: List<PhotoControlStatus>
    ): List<PhotoControl>

    @Query("""
        SELECT pc FROM PhotoControl pc 
        WHERE pc.status = 'PENDING' AND pc.deadlineAt < :now
    """)
    fun findAllExpiredPending(now: LocalDateTime): List<PhotoControl>

    fun findAllByOrderByRequestedAtDesc(): List<PhotoControl>
}