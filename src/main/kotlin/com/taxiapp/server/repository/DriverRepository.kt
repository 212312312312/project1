package com.taxiapp.server.repository

import com.taxiapp.server.model.user.Driver
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.Optional

@Repository
interface DriverRepository : JpaRepository<Driver, Long> {

    @Query("""
        SELECT d FROM Driver d 
        WHERE d.currentLatitude IS NOT NULL 
        AND d.currentLatitude != 0.0 
        AND d.lastUpdate > :threshold
    """)
    fun findAllActiveOnMap(threshold: java.time.LocalDateTime): List<Driver>

    @Modifying
    @Transactional
    @Query("UPDATE Driver d SET d.currentLatitude = :lat, d.currentLongitude = :lng, d.lastUpdate = :now WHERE d.id = :id")
    fun updateCoordinatesAndTimestamp(id: Long, lat: Double, lng: Double, now: java.time.LocalDateTime)

    @Modifying
    @Transactional
    @Query("UPDATE Driver d SET d.currentLatitude = null, d.currentLongitude = null WHERE d.id = :id")
    fun clearCoordinates(id: Long)

    // --- ОНОВЛЕНІ МЕТОДИ ПОШУКУ ---
    fun findByUserPhone(phone: String): Driver?
    fun findByUserLogin(login: String): Driver?
}