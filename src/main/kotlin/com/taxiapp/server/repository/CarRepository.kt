package com.taxiapp.server.repository

import com.taxiapp.server.model.user.Car
import com.taxiapp.server.model.user.Driver
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface CarRepository : JpaRepository<Car, Long> {
    // Найти все машины водителя (и активные, и на проверке)
    fun findAllByDriver(driver: Driver): List<Car>
}