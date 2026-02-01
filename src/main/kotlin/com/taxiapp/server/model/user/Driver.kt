package com.taxiapp.server.model.user

import com.fasterxml.jackson.annotation.JsonIgnore
import com.taxiapp.server.model.driver.DriverActivityHistory
import com.taxiapp.server.model.enums.DriverSearchMode
import com.taxiapp.server.model.order.CarTariff
import com.taxiapp.server.model.order.TaxiOrder
import com.taxiapp.server.model.sector.Sector
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "drivers")
@PrimaryKeyJoinColumn(name = "id")
class Driver : User() {
    
    @Column(nullable = false)
    var isOnline: Boolean = false

    // --- РЕЙТИНГ ---
    @Column(nullable = false, columnDefinition = "double precision default 5.0")
    var rating: Double = 5.0

    @Column(nullable = false, columnDefinition = "integer default 0")
    var ratingCount: Int = 0
    // ------------------

    // --- ГЕОЛОКАЦІЯ ---
    var latitude: Double? = null
    var longitude: Double? = null
    var bearing: Float? = 0.0f
    var lastUpdate: LocalDateTime? = null
    // ------------------

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

    // АКТИВНОЕ АВТО (на котором водитель сейчас)
    @OneToOne(cascade = [CascadeType.ALL], orphanRemoval = false) // orphanRemoval = false, чтобы не удалять машину при смене
    @JoinColumn(name = "car_id", referencedColumnName = "id")
    var car: Car? = null

    // ВЕСЬ ГАРАЖ (Список всех машин водителя)
    @OneToMany(mappedBy = "driver", fetch = FetchType.LAZY)
    @JsonIgnore // Чтобы избежать бесконечного цикла
    var cars: MutableList<Car> = mutableListOf()

    @OneToMany(mappedBy = "driver")
    val orders: List<TaxiOrder> = emptyList()
    
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "driver_tariffs",
        joinColumns = [JoinColumn(name = "driver_id")],
        inverseJoinColumns = [JoinColumn(name = "tariff_id")]
    )
    var allowedTariffs: MutableSet<CarTariff> = mutableSetOf()

    // --- НАЛАШТУВАННЯ ПОШУКУ ---

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(20) default 'OFFLINE'")
    var searchMode: DriverSearchMode = DriverSearchMode.OFFLINE

    @Column(nullable = false, columnDefinition = "double precision default 3.0")
    var searchRadius: Double = 3.0 

    // Налаштування "Додому"
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
    
    @OneToMany(mappedBy = "driver", cascade = [CascadeType.ALL], fetch = FetchType.LAZY)
    var activityHistory: MutableList<DriverActivityHistory> = mutableListOf()

    override fun isAccountNonLocked(): Boolean {
        if (isBlocked) return false
        val expires = tempBlockExpiresAt
        if (expires != null) {
            return !LocalDateTime.now().isBefore(expires)
        }
        return true
    }
}