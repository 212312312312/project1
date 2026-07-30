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

@Repository
interface DriverRepository : JpaRepository<Driver, Long> {

    fun findByUuid(uuid: String): java.util.Optional<Driver>

    fun findAllByRegistrationStatusNot(status: RegistrationStatus): List<Driver>

    fun findAllByRegistrationStatus(status: RegistrationStatus): List<Driver>

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

    @Query("SELECT d FROM Driver d WHERE d.deletionRequestedAt IS NOT NULL AND d.deletionRequestedAt < :threshold")
    fun findAllPendingDeletionBefore(@Param("threshold") threshold: LocalDateTime): List<Driver>

    @Query("SELECT d FROM Driver d WHERE d.deletionRequestedAt IS NOT NULL")
    fun findAllPendingDeletion(): List<Driver>
}