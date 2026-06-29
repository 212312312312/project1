package com.taxiapp.server.repository

import com.taxiapp.server.model.analytics.ClientAppEvent
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface ClientAppEventRepository : JpaRepository<ClientAppEvent, Long> {

    @Query("SELECT e.screenName, COUNT(e), AVG(e.durationSeconds) FROM ClientAppEvent e GROUP BY e.screenName")
    fun getScreenStats(): List<Array<Any>>
}