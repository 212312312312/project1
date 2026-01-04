package com.taxiapp.server.repository

import com.taxiapp.server.model.user.Driver
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
interface DriverRepository : JpaRepository<Driver, Long> {
    
    // Ищем всех, у кого есть координаты (не важно, онлайн они или нет)
    @Query("SELECT d FROM Driver d WHERE d.currentLatitude IS NOT NULL AND d.currentLongitude IS NOT NULL AND d.currentLatitude != 0.0")
    fun findAllWithCoordinates(): List<Driver>

    fun findByUserPhone(phone: String): Optional<Driver>
}