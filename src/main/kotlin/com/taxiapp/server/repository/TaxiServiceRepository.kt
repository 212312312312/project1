package com.taxiapp.server.repository

import com.taxiapp.server.model.services.TaxiServiceEntity
import org.springframework.data.jpa.repository.JpaRepository

interface TaxiServiceRepository : JpaRepository<TaxiServiceEntity, Long> {
    fun findAllByIsActiveTrue(): List<TaxiServiceEntity>
}