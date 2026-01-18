package com.taxiapp.server.model.sector

import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import java.time.LocalDateTime

@Entity
@Table(name = "sectors")
class Sector(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(nullable = false)
    var name: String,

    // --- НОВЕ ПОЛЕ ---
    // true = Місто (звичайний тариф), false = За містом (спец. тариф pricePerKmOutCity)
    @Column(nullable = false)
    var isCity: Boolean = true, 
    // ------------------

    @OneToMany(mappedBy = "sector", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.EAGER)
    var points: MutableList<SectorPoint> = mutableListOf(),

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()
)