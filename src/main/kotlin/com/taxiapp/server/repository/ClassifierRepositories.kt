package com.taxiapp.server.repository

import com.taxiapp.server.model.classifier.CarBrand
import com.taxiapp.server.model.classifier.CarClassifierRule
import com.taxiapp.server.model.classifier.CarModel
import com.taxiapp.server.model.classifier.City
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface CityRepository : JpaRepository<City, Long> {
    fun findAllByOrderByNameAsc(): List<City>
    fun findByName(name: String): City?
}

@Repository
interface CarBrandRepository : JpaRepository<CarBrand, Long> {
    fun findAllByOrderByNameAsc(): List<CarBrand>
}

@Repository
interface CarModelRepository : JpaRepository<CarModel, Long> {
    fun findByBrandIdOrderByNameAsc(brandId: Long): List<CarModel>
}

@Repository
interface CarClassifierRuleRepository : JpaRepository<CarClassifierRule, Long> {
    @Query("""
        SELECT r FROM CarClassifierRule r 
        WHERE r.model.id = :modelId 
          AND (r.yearFrom IS NULL OR :year >= r.yearFrom)
          AND (r.yearTo IS NULL OR :year <= r.yearTo)
    """)
    fun findMatchingRules(modelId: Long, year: Int): List<CarClassifierRule>
}