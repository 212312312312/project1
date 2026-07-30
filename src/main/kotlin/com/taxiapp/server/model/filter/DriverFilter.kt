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
    
    var isActive: Boolean = false, // Загальний статус "Ввімкнено"

    // РЕЖИМИ (прапорці)
    var isEther: Boolean = false,  // <--- НОВЕ ПОЛЕ: Перегляд в ефірі
    var isAuto: Boolean = false,   // Робот (одноразовий)
    var isCycle: Boolean = false,  // Робот (циклічний)

    // Блок ЗВІДКИ
    var fromType: String,
    var fromDistance: Double? = null,
    
    @ElementCollection
    @CollectionTable(name = "filter_from_sectors", joinColumns = [JoinColumn(name = "filter_id")])
    var fromSectors: MutableList<Long> = mutableListOf(),

    // Блок КУДИ
    @ElementCollection
    @CollectionTable(name = "filter_to_sectors", joinColumns = [JoinColumn(name = "filter_id")])
    var toSectors: MutableList<Long> = mutableListOf(),

    // Блок ТАРИФ
    var tariffType: String,
    
    var minPrice: Double? = null,
    var minPricePerKm: Double? = null,

    var complexMinPrice: Double? = null,
    var complexKmInMin: Double? = null, 
    var complexPriceKmCity: Double? = null,
    var complexPriceKmSuburbs: Double? = null,

    var paymentType: String,

    @CreationTimestamp
    var createdAt: LocalDateTime = LocalDateTime.now()
)