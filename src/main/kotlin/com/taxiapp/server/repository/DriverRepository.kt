package com.taxiapp.server.repository

import com.taxiapp.server.model.user.Driver
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.Optional

@Repository
interface DriverRepository : JpaRepository<Driver, Long> {

    // ИСПРАВЛЕНО: d.latitude вместо d.currentLatitude
    @Query("""
        SELECT d FROM Driver d 
        WHERE d.latitude IS NOT NULL 
        AND d.latitude != 0.0 
        AND d.lastUpdate > :threshold
    """)
    fun findAllActiveOnMap(@Param("threshold") threshold: LocalDateTime): List<Driver>

    // ИСПРАВЛЕНО: d.latitude вместо d.currentLatitude
    @Modifying
    @Transactional
    @Query("UPDATE Driver d SET d.latitude = :lat, d.longitude = :lng, d.lastUpdate = :now WHERE d.id = :id")
    fun updateCoordinatesAndTimestamp(
        @Param("id") id: Long, 
        @Param("lat") lat: Double, 
        @Param("lng") lng: Double, 
        @Param("now") now: LocalDateTime
    )

    @Modifying
    @Transactional
    @Query("UPDATE Driver d SET d.latitude = null, d.longitude = null WHERE d.id = :id")
    fun clearCoordinates(@Param("id") id: Long)

    fun findByUserPhone(phone: String): Driver?
    fun findByUserLogin(login: String): Driver?

    @Modifying
    @Transactional
    @Query("UPDATE Driver d SET d.homeRidesLeft = 2, d.lastHomeUsageDate = null") 
    fun resetAllHomeLimits()

    fun findAllByHomeSectorsId(sectorId: Long): List<Driver>

    // Native Query: здесь мы используем имена КОЛОНОК в БД.
    // Если Hibernate создавал таблицу, колонки называются как поля (latitude).
    // Если таблица старая, возможно там current_latitude. 
    // Я ставлю latitude, так как мы обновили модель. Если упадет - значит колонки в БД старые.
    @Query(value = """
        SELECT d.*, u.* FROM drivers d
        JOIN users u ON d.id = u.id
        LEFT JOIN driver_home_sectors dhs ON d.id = dhs.driver_id
        WHERE d.is_online = true 
          AND u.is_blocked = false 
          AND d.activity_score > -1
          
          -- Игнорируем OFFLINE
          AND d.search_mode != 'OFFLINE'
          
          AND d.latitude IS NOT NULL 
          AND d.longitude IS NOT NULL
          
          AND (coalesce(:rejectedDriverIds) IS NULL OR d.id NOT IN (:rejectedDriverIds))

          AND (
              (d.search_mode = 'CHAIN')
              OR
              (d.search_mode = 'MANUAL')
              OR 
              (d.search_mode = 'HOME' AND d.home_rides_left > 0 AND dhs.sector_id = :destinationSectorId)
          )
        
        -- Радиус (Haversine)
        AND (
            6371 * acos(
                least(1.0, greatest(-1.0, 
                    cos(radians(:pickupLat)) * cos(radians(d.latitude)) * cos(radians(d.longitude) - radians(:pickupLng)) + 
                    sin(radians(:pickupLat)) * sin(radians(d.latitude))
                ))
            )
        ) <= d.search_radius
        
        ORDER BY (
            6371 * acos(
                least(1.0, greatest(-1.0, 
                    cos(radians(:pickupLat)) * cos(radians(d.latitude)) * cos(radians(d.longitude) - radians(:pickupLng)) + 
                    sin(radians(:pickupLat)) * sin(radians(d.latitude))
                ))
            )
        ) ASC
        LIMIT 1
    """, nativeQuery = true)
    fun findBestDriverForOrder(
        @Param("pickupLat") pickupLat: Double, 
        @Param("pickupLng") pickupLng: Double, 
        @Param("destinationSectorId") destinationSectorId: Long?,
        @Param("rejectedDriverIds") rejectedDriverIds: List<Long>?
    ): Optional<Driver>
}