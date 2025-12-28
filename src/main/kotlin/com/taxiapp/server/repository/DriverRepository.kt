package com.taxiapp.server.repository

import com.taxiapp.server.model.user.Driver
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface DriverRepository : JpaRepository<Driver, Long> {
    
    fun findByUserPhoneContaining(userPhone: String): List<Driver>

    @Query("SELECT d FROM Driver d WHERE d.isOnline = true")
    fun findAllOnline(): List<Driver>
}