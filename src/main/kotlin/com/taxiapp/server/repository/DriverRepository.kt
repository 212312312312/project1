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
    
    // 1. ЗАПРОС ДЛЯ КАРТЫ:
    // Убедись, что тут НЕТ условия "d.isOnline = true".
    // Мы берем всех, у кого есть валидные координаты.
    @Query("SELECT d FROM Driver d WHERE d.currentLatitude IS NOT NULL AND d.currentLongitude IS NOT NULL AND d.currentLatitude != 0.0")
    fun findAllWithCoordinates(): List<Driver>

    fun findByUserPhone(phone: String): Optional<Driver>

    // 2. ПРЯМОЕ ОБНОВЛЕНИЕ (ВЕРНУЛИ ЭТОТ МЕТОД):
    // Пишет координаты в базу моментально, минуя кэш Hibernate.
    @Modifying
    @Transactional
    @Query("UPDATE Driver d SET d.currentLatitude = :lat, d.currentLongitude = :lng WHERE d.id = :id")
    fun updateCoordinates(id: Long, lat: Double, lng: Double)
}