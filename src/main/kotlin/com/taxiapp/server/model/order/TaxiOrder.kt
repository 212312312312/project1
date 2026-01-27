package com.taxiapp.server.model.order

import com.taxiapp.server.model.enums.OrderStatus
import com.taxiapp.server.model.user.Client
import com.taxiapp.server.model.user.Driver
import com.taxiapp.server.model.sector.Sector
import com.taxiapp.server.model.services.TaxiServiceEntity
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import java.time.LocalDateTime

@Entity
@Table(name = "taxi_orders")
class TaxiOrder(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    var client: Client,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_id")
    var driver: Driver? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "offered_driver_id")
    var offeredDriver: Driver? = null,

    var offerExpiresAt: LocalDateTime? = null,

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "order_rejected_drivers", joinColumns = [JoinColumn(name = "order_id")])
    @Column(name = "driver_id")
    var rejectedDriverIds: MutableSet<Long> = mutableSetOf(),

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "destination_sector_id")
    var destinationSector: Sector? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "origin_sector_id")
    var originSector: Sector? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: OrderStatus = OrderStatus.REQUESTED,

    @Column(name = "from_address", nullable = false)
    var fromAddress: String,

    @Column(name = "to_address", nullable = false)
    var toAddress: String,
    
    @Column(nullable = true) 
    var originLat: Double? = null, 
    
    @Column(nullable = true)
    var originLng: Double? = null,
    
    @Column(nullable = true)
    var destLat: Double? = null,
    
    @Column(nullable = true)
    var destLng: Double? = null,

    @Column(nullable = true, length = 4096)
    var googleRoutePolyline: String? = null,

    @Column(nullable = false)
    var price: Double = 0.0, 
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tariff_id", nullable = false)
    var tariff: CarTariff, 
    
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    var completedAt: LocalDateTime? = null,

    @Column(nullable = false)
    var appliedDiscount: Double = 0.0,

    @Column(name = "is_promo_code_used")
    var isPromoCodeUsed: Boolean = false,

    @Column(name = "accepted_at")
    var acceptedAt: LocalDateTime? = null,

    @Column(name = "distance_meters")
    var distanceMeters: Int? = null,

    @Column(name = "duration_seconds")
    var durationSeconds: Int? = null,

    @Column(name = "client_comment", length = 400)
    var comment: String? = null,

    @Column(name = "tariff_name")
    var tariffName: String? = null,
    
    @Column(name = "payment_method")
    var paymentMethod: String = "CASH",

    @Column(nullable = false)
    var addedValue: Double = 0.0,

    // --- ФЛАГИ ОЦЕНКИ (НОВОЕ) ---
    @Column(nullable = false)
    var isRatedByClient: Boolean = false,

    @Column(nullable = false)
    var isRatedByDriver: Boolean = false,
    // ----------------------------

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "order_services_link",
        joinColumns = [JoinColumn(name = "order_id")],
        inverseJoinColumns = [JoinColumn(name = "service_id")]
    )
    val selectedServices: MutableList<TaxiServiceEntity> = mutableListOf(),

) {
    @OneToMany(mappedBy = "order", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.EAGER)
    var stops: MutableList<OrderStop> = mutableListOf()
    
    protected constructor() : this(
        client = Client(), 
        fromAddress = "",
        toAddress = "",
        tariff = CarTariff(name = "Stub", basePrice = 0.0, pricePerKm = 0.0) 
    )
}