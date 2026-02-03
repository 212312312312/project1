package com.taxiapp.server.repository

import com.taxiapp.server.model.enums.RegistrationStatus
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


    fun findAllByRegistrationStatusNot(status: RegistrationStatus): List<Driver>

    // 2. Знайти всіх з конкретним статусом (для списку "Заявки")
    fun findAllByRegistrationStatus(status: RegistrationStatus): List<Driver>

    @Query("""
        SELECT d FROM Driver d 
        WHERE d.latitude IS NOT NULL 
        AND d.latitude != 0.0 
        AND d.lastUpdate > :threshold
    """)
    fun findAllActiveOnMap(@Param("threshold") threshold: LocalDateTime): List<Driver>

    // --- ЛАНЦЮГ (CHAIN) ---
    // Исправлено: используем d.search_radius
    @Query(value = """
        SELECT d.*, u.* FROM drivers d
        JOIN users u ON d.id = u.id
        JOIN taxi_orders o ON o.driver_id = d.id
        WHERE d.is_online = true 
          AND d.activity_score > 0
          AND o.status = 'IN_PROGRESS'
          AND (coalesce(:rejectedDriverIds) IS NULL OR d.id NOT IN (:rejectedDriverIds))
          AND (
             6371 * acos(least(1.0, greatest(-1.0, 
                cos(radians(:pickupLat)) * cos(radians(o.dest_lat)) * cos(radians(o.dest_lng) - radians(:pickupLng)) + 
                sin(radians(:pickupLat)) * sin(radians(o.dest_lat))
             ))) <= d.search_radius
          )
        ORDER BY (
             6371 * acos(least(1.0, greatest(-1.0, 
                cos(radians(:pickupLat)) * cos(radians(o.dest_lat)) * cos(radians(o.dest_lng) - radians(:pickupLng)) + 
                sin(radians(:pickupLat)) * sin(radians(o.dest_lat))
             )))
        ) ASC
        LIMIT 1
    """, nativeQuery = true)
    fun findBestChainDriver(
        @Param("pickupLat") pickupLat: Double, 
        @Param("pickupLng") pickupLng: Double,
        @Param("rejectedDriverIds") rejectedDriverIds: List<Long>?
    ): Optional<Driver>

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

    // НОВЫЙ МЕТОД: Возвращает список кандидатов (для умного выбора)
    @Query(value = """
        SELECT d.*, u.* FROM drivers d
        JOIN users u ON d.id = u.id
        LEFT JOIN driver_home_sectors dhs ON d.id = dhs.driver_id
        WHERE d.is_online = true 
          AND u.is_blocked = false 
          AND d.activity_score > -1
          AND d.search_mode != 'OFFLINE'
          AND d.latitude IS NOT NULL 
          AND d.longitude IS NOT NULL
          AND (coalesce(:rejectedDriverIds) IS NULL OR d.id NOT IN (:rejectedDriverIds))
          AND (
              (d.search_mode = 'CHAIN') OR
              (d.search_mode = 'MANUAL') OR 
              (d.search_mode = 'HOME' AND d.home_rides_left > 0 AND dhs.sector_id = :destinationSectorId)
          )
        AND (
            6371 * acos(least(1.0, greatest(-1.0, 
                cos(radians(:pickupLat)) * cos(radians(d.latitude)) * cos(radians(d.longitude) - radians(:pickupLng)) + 
                sin(radians(:pickupLat)) * sin(radians(d.latitude))
            ))) <= d.search_radius
        )
        ORDER BY (
            6371 * acos(least(1.0, greatest(-1.0, 
                cos(radians(:pickupLat)) * cos(radians(d.latitude)) * cos(radians(d.longitude) - radians(:pickupLng)) + 
                sin(radians(:pickupLat)) * sin(radians(d.latitude))
            )))
        ) ASC
        LIMIT 5
    """, nativeQuery = true)
    fun findBestDriversCandidates(
        @Param("pickupLat") pickupLat: Double, 
        @Param("pickupLng") pickupLng: Double, 
        @Param("destinationSectorId") destinationSectorId: Long?,
        @Param("rejectedDriverIds") rejectedDriverIds: List<Long>?
    ): List<Driver>

    // СТАРЫЙ МЕТОД (Возвращен для совместимости, чтобы убрать ошибку на строке 812)
    @Query(value = """
        SELECT d.*, u.* FROM drivers d
        JOIN users u ON d.id = u.id
        LEFT JOIN driver_home_sectors dhs ON d.id = dhs.driver_id
        WHERE d.is_online = true 
          AND u.is_blocked = false 
          AND d.activity_score > -1
          AND d.search_mode != 'OFFLINE'
          AND d.latitude IS NOT NULL 
          AND d.longitude IS NOT NULL
          AND (coalesce(:rejectedDriverIds) IS NULL OR d.id NOT IN (:rejectedDriverIds))
          AND (
              (d.search_mode = 'CHAIN') OR
              (d.search_mode = 'MANUAL') OR 
              (d.search_mode = 'HOME' AND d.home_rides_left > 0 AND dhs.sector_id = :destinationSectorId)
          )
        AND (
            6371 * acos(least(1.0, greatest(-1.0, 
                cos(radians(:pickupLat)) * cos(radians(d.latitude)) * cos(radians(d.longitude) - radians(:pickupLng)) + 
                sin(radians(:pickupLat)) * sin(radians(d.latitude))
            ))) <= d.search_radius
        )
        ORDER BY (
            6371 * acos(least(1.0, greatest(-1.0, 
                cos(radians(:pickupLat)) * cos(radians(d.latitude)) * cos(radians(d.longitude) - radians(:pickupLng)) + 
                sin(radians(:pickupLat)) * sin(radians(d.latitude))
            )))
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