package com.taxiapp.server.repository

import com.taxiapp.server.model.filter.DriverFilter
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface DriverFilterRepository : JpaRepository<DriverFilter, Long> {

    fun findAllByDriverId(driverId: Long): List<DriverFilter>

    @Query("""
        SELECT f FROM DriverFilter f 
        JOIN f.driver d 
        LEFT JOIN f.fromSectors fs
        WHERE f.isActive = true 
          AND (f.isAuto = true OR f.isCycle = true)
          AND d.isOnline = true 
          AND d.activityScore > 0
          AND (coalesce(:rejectedDriverIds) IS NULL OR d.id NOT IN (:rejectedDriverIds))
          AND (f.minPrice IS NULL OR :orderPrice >= f.minPrice)
          AND (
            (f.fromType = 'DISTANCE' AND 
              (6371 * acos(least(1.0, greatest(-1.0, 
                cos(radians(:orderLat)) * cos(radians(d.latitude)) * cos(radians(d.longitude) - radians(:orderLng)) + 
                sin(radians(:orderLat)) * sin(radians(d.latitude))
              ))) <= f.fromDistance)
            )
            OR
            (f.fromType = 'SECTORS' AND fs = :orderSectorId) 
          )
    """)
    fun findMatchingAutoFilters(
        @Param("orderLat") orderLat: Double,
        @Param("orderLng") orderLng: Double,
        @Param("orderSectorId") orderSectorId: Long?,
        @Param("orderPrice") orderPrice: Double,
        @Param("rejectedDriverIds") rejectedDriverIds: List<Long>?
    ): List<DriverFilter>
    
    // Старый метод
    @Query("SELECT f FROM DriverFilter f JOIN f.driver d WHERE f.isActive = true AND (f.isAuto = true OR f.isCycle = true) AND d.isOnline = true AND d.activityScore > 0")
    fun findAllActiveAutoFilters(): List<DriverFilter>
}