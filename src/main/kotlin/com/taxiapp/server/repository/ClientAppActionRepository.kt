package com.taxiapp.server.repository

import com.taxiapp.server.model.analytics.ClientAppAction
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface ClientAppActionRepository : JpaRepository<ClientAppAction, Long> {

    @Query("""
        SELECT a.actionName, a.actionValue, COUNT(a) 
        FROM ClientAppAction a 
        GROUP BY a.actionName, a.actionValue
    """)
    fun getActionStats(): List<Array<Any>>
}