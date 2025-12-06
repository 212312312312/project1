package com.taxiapp.server.model.order

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "car_tariffs")
data class CarTariff(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(unique = true, nullable = false)
    var name: String, 
    
    // ... (basePrice, pricePerKm, и т.д.) ...
    @Column(nullable = false)
    var basePrice: Double,
    @Column(nullable = false)
    var pricePerKm: Double,
    @Column(nullable = false)
    var freeWaitingMinutes: Int = 3,
    @Column(nullable = false)
    var pricePerWaitingMinute: Double = 2.0,
    @Column(nullable = false)
    var isActive: Boolean = true,
    
    // --- НОВОЕ ПОЛЕ ---
    @Column(nullable = true)
    var imageUrl: String? = null, // Имя файла (напр. "uuid.png")
    
    val createdAt: LocalDateTime = LocalDateTime.now()
)