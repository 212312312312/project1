package com.taxiapp.server.model.filter

import com.taxiapp.server.model.user.Driver
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import java.time.LocalDateTime

@Entity
@Table(name = "driver_filters")
class DriverFilter(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_id", nullable = false)
    var driver: Driver,

    var name: String,
    var isActive: Boolean = false,

    // Блок ЗВІДКИ
    var fromType: String, // "DISTANCE" или "SECTORS"
    var fromDistance: Double? = null,
    
    @ElementCollection
    @CollectionTable(name = "filter_from_sectors", joinColumns = [JoinColumn(name = "filter_id")])
    var fromSectors: MutableList<Long> = mutableListOf(),

    // Блок КУДИ
    @ElementCollection
    @CollectionTable(name = "filter_to_sectors", joinColumns = [JoinColumn(name = "filter_id")])
    var toSectors: MutableList<Long> = mutableListOf(),

    // Блок ТАРИФ
    var tariffType: String, // "SIMPLE" или "COMPLEX"
    
    // Простой
    var minPrice: Double? = null,
    var minPricePerKm: Double? = null,


    var complexMinPrice: Double? = null,
    var complexKmInMin: Double? = null, 
    var complexPriceKmCity: Double? = null,
    var complexPriceKmSuburbs: Double? = null,

    // ОПЛАТА
    var paymentType: String, // "CASH", "CARD", "ANY"

    @CreationTimestamp
    var createdAt: LocalDateTime = LocalDateTime.now()
)