package com.taxiapp.server.repository

import com.taxiapp.server.model.analytics.ClientAppAction
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
interface ClientAppActionRepository : JpaRepository<ClientAppAction, Long> {

    @Query("""
        SELECT a.actionName, a.actionValue, COUNT(a), COUNT(DISTINCT a.sessionId), COUNT(DISTINCT a.clientId)
        FROM ClientAppAction a 
        GROUP BY a.actionName, a.actionValue
    """)
    fun getActionStats(): List<Array<Any>>

    @Query("""
        SELECT a.actionName, a.actionValue, COUNT(a), COUNT(DISTINCT a.sessionId), COUNT(DISTINCT a.clientId)
        FROM ClientAppAction a 
        WHERE a.createdAt BETWEEN :startDate AND :endDate
        GROUP BY a.actionName, a.actionValue
    """)
    fun getActionStatsByPeriod(
        @Param("startDate") startDate: LocalDateTime,
        @Param("endDate") endDate: LocalDateTime
    ): List<Array<Any>>

    @Query("""
        SELECT COUNT(a) 
        FROM ClientAppAction a 
        WHERE a.actionName = :actionName 
          AND a.createdAt BETWEEN :startDate AND :endDate
    """)
    fun countByActionNameAndPeriod(
        @Param("actionName") actionName: String,
        @Param("startDate") startDate: LocalDateTime,
        @Param("endDate") endDate: LocalDateTime
    ): Long
}