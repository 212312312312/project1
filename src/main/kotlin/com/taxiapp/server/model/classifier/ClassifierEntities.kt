package com.taxiapp.server.model.classifier

import com.taxiapp.server.model.enums.CityGrade
import com.taxiapp.server.model.enums.TariffStatus
import jakarta.persistence.*

@Entity
@Table(name = "cities")
class City(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false, unique = true)
    val name: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val grade: CityGrade
)

@Entity
@Table(name = "car_brands")
class CarBrand(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false, unique = true)
    val name: String
)

@Entity
@Table(name = "car_models")
class CarModel(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_id", nullable = false)
    val brand: CarBrand,

    @Column(nullable = false)
    val name: String
)

@Entity
@Table(name = "car_classifier_rules")
class CarClassifierRule(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "model_id", nullable = false)
    val model: CarModel,

    val generation: String? = null,
    val segment: String? = null,
    val yearFrom: Int? = null,
    val yearTo: Int? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "status_grade_a", nullable = false)
    val statusGradeA: TariffStatus,

    @Enumerated(EnumType.STRING)
    @Column(name = "status_grade_b", nullable = false)
    val statusGradeB: TariffStatus
)