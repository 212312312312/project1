package com.taxiapp.server.repository

import com.taxiapp.server.model.analytics.ClientAppEvent
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
interface ClientAppEventRepository : JpaRepository<ClientAppEvent, Long> {

    @Query("""
        SELECT e.screenName, COUNT(e), AVG(e.durationSeconds) 
        FROM ClientAppEvent e 
        WHERE e.durationSeconds <= 900 
        GROUP BY e.screenName
        ORDER BY COUNT(e) DESC
    """)
    fun getScreenStats(): List<Array<Any>>

    @Query("""
        SELECT e.screenName, COUNT(e), AVG(e.durationSeconds) 
        FROM ClientAppEvent e 
        WHERE e.durationSeconds <= 900 
          AND e.createdAt BETWEEN :startDate AND :endDate
        GROUP BY e.screenName
        ORDER BY COUNT(e) DESC
    """)
    fun getScreenStatsByPeriod(
        @Param("startDate") startDate: LocalDateTime,
        @Param("endDate") endDate: LocalDateTime
    ): List<Array<Any>>
}