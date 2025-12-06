package com.taxiapp.server.model.user

import com.taxiapp.server.model.order.CarTariff
import com.taxiapp.server.model.order.TaxiOrder
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "drivers")
class Driver : User() { 
    
    @Column(nullable = false)
    var isOnline: Boolean = false

    var currentLatitude: Double? = null
    var currentLongitude: Double? = null

    var tempBlockExpiresAt: LocalDateTime? = null

    @OneToOne(cascade = [CascadeType.ALL], orphanRemoval = true)
    @JoinColumn(name = "car_id", referencedColumnName = "id")
    var car: Car? = null

    @OneToMany(mappedBy = "driver")
    val orders: List<TaxiOrder> = emptyList()
    
    // --- ОНОВЛЕННЯ ---
    // Ми замінили @ManyToOne на @ManyToMany
    // Це створить нову таблицю "driver_tariffs" (driver_id, tariff_id)
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "driver_tariffs",
        joinColumns = [JoinColumn(name = "driver_id")],
        inverseJoinColumns = [JoinColumn(name = "tariff_id")]
    )
    var allowedTariffs: MutableSet<CarTariff> = mutableSetOf()
    // --- Кінець оновлення ---

    override fun isAccountNonLocked(): Boolean {
        if (isBlocked) {
            return false 
        }

        val expires = tempBlockExpiresAt
        if (expires != null) {
            val isLocked = LocalDateTime.now().isBefore(expires)
            return !isLocked
        }
        
        return true
    }
}