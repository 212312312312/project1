package com.taxiapp.server.repository

import com.taxiapp.server.model.user.Driver
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.Optional

@Repository
interface DriverRepository : JpaRepository<Driver, Long> {

    @Query("""
        SELECT d FROM Driver d 
        WHERE d.currentLatitude IS NOT NULL 
        AND d.currentLatitude != 0.0 
        AND d.lastUpdate > :threshold
    """)
    fun findAllActiveOnMap(threshold: LocalDateTime): List<Driver>

    @Modifying
    @Transactional
    @Query("UPDATE Driver d SET d.currentLatitude = :lat, d.currentLongitude = :lng, d.lastUpdate = :now WHERE d.id = :id")
    fun updateCoordinatesAndTimestamp(id: Long, lat: Double, lng: Double, now: LocalDateTime)

    @Modifying
    @Transactional
    @Query("UPDATE Driver d SET d.currentLatitude = null, d.currentLongitude = null WHERE d.id = :id")
    fun clearCoordinates(id: Long)

    fun findByUserPhone(phone: String): Driver?
    fun findByUserLogin(login: String): Driver?

    @Modifying
    @Transactional
    @Query("UPDATE Driver d SET d.homeRidesLeft = 2") 
    fun resetAllHomeLimits()

    // --- ВИПРАВЛЕНИЙ SMART DISPATCH ---
    // ЗМІНА: SELECT d.*, u.* (вибираємо поля і водія, і юзера)
    @Query(value = """
        SELECT d.*, u.* FROM drivers d
        JOIN users u ON d.id = u.id
        LEFT JOIN driver_home_sectors dhs ON d.id = dhs.driver_id
        WHERE d.is_online = true
        AND u.is_blocked = false
        AND d.activity_score > 400
        AND (
            (d.search_mode = 'CHAIN')
            OR 
            (d.search_mode = 'HOME' AND d.home_rides_left > 0 AND dhs.sector_id = :destinationSectorId)
        )
        AND (6371 * acos(cos(radians(:pickupLat)) * cos(radians(d.current_latitude)) * cos(radians(d.current_longitude) - radians(:pickupLng)) + sin(radians(:pickupLat)) * sin(radians(d.current_latitude)))) <= d.search_radius
        ORDER BY (6371 * acos(cos(radians(:pickupLat)) * cos(radians(d.current_latitude)) * cos(radians(d.current_longitude) - radians(:pickupLng)) + sin(radians(:pickupLat)) * sin(radians(d.current_latitude)))) ASC
        LIMIT 1
    """, nativeQuery = true)
    fun findBestDriverForOrder(
        pickupLat: Double, 
        pickupLng: Double, 
        destinationSectorId: Long?
    ): Optional<Driver>
}