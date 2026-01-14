package com.taxiapp.server.model.user

import com.taxiapp.server.model.enums.DriverSearchMode
import com.taxiapp.server.model.order.CarTariff
import com.taxiapp.server.model.order.TaxiOrder
import com.taxiapp.server.model.sector.Sector
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "drivers")
class Driver : User() { 
    
    @Column(nullable = false)
    var isOnline: Boolean = false

    var currentLatitude: Double? = null
    var currentLongitude: Double? = null
    var lastUpdate: java.time.LocalDateTime? = null

    @Column(nullable = true)
    var photoUrl: String? = null

    @Column(nullable = false)
    var completedRides: Int = 0

    @Column(name = "rnokpp")
    var rnokpp: String? = null

    @Column(name = "driver_license")
    var driverLicense: String? = null
    
    @Column(nullable = false, columnDefinition = "integer default 1000")
    var activityScore: Int = 1000

    var tempBlockExpiresAt: LocalDateTime? = null

    @OneToOne(cascade = [CascadeType.ALL], orphanRemoval = true)
    @JoinColumn(name = "car_id", referencedColumnName = "id")
    var car: Car? = null

    @OneToMany(mappedBy = "driver")
    val orders: List<TaxiOrder> = emptyList()
    
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "driver_tariffs",
        joinColumns = [JoinColumn(name = "driver_id")],
        inverseJoinColumns = [JoinColumn(name = "tariff_id")]
    )
    var allowedTariffs: MutableSet<CarTariff> = mutableSetOf()

    // --- НОВІ ПОЛЯ ДЛЯ ЛАНЦЮГА ТА ДОДОМУ ---

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(20) default 'MANUAL'")
    var searchMode: DriverSearchMode = DriverSearchMode.MANUAL

    @Column(nullable = false, columnDefinition = "double precision default 3.0")
    var searchRadius: Double = 3.0 

    // Налаштування "Додому" (тепер список секторів)
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "driver_home_sectors",
        joinColumns = [JoinColumn(name = "driver_id")],
        inverseJoinColumns = [JoinColumn(name = "sector_id")]
    )
    var homeSectors: MutableSet<Sector> = mutableSetOf()

    @Column(nullable = false, columnDefinition = "integer default 2")
    var homeRidesLeft: Int = 2 

    @Column(nullable = true)
    var lastHomeUsageDate: LocalDateTime? = null 
    // ---------------------------------------

    override fun isAccountNonLocked(): Boolean {
        if (isBlocked) return false
        if (activityScore <= 0) return false 
        val expires = tempBlockExpiresAt
        if (expires != null) {
            return !LocalDateTime.now().isBefore(expires)
        }
        return true
    }
}