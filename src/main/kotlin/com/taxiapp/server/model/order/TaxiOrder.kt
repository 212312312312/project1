package com.taxiapp.server.model.order

import com.taxiapp.server.model.enums.OrderStatus
import com.taxiapp.server.model.user.Client
import com.taxiapp.server.model.user.Driver
import com.taxiapp.server.model.sector.Sector
import com.taxiapp.server.model.services.TaxiServiceEntity
import jakarta.persistence.*
import org.hibernate.annotations.Fetch
import org.hibernate.annotations.FetchMode
import org.hibernate.annotations.CreationTimestamp
import java.time.LocalDateTime
import java.util.UUID // <-- ДОБАВИЛИ ИМПОРТ

@Entity
@Table(name = "taxi_orders")
class TaxiOrder(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    // --- НОВОЕ ПОЛЕ ДЛЯ ПУБЛИЧНОГО API ---
    @Column(name = "uuid", unique = true, nullable = false, updatable = false)
    val uuid: UUID = UUID.randomUUID(),
    // -------------------------------------

    @Column(name = "scheduled_at")
    var scheduledAt: LocalDateTime? = null,

    @Column(name = "is_driver_confirmed")
    var isDriverConfirmed: Boolean? = false,

    @Column(name = "confirmation_requested_at")
    var confirmationRequestedAt: LocalDateTime? = null, 

    @Column(name = "arrived_at")
    var arrivedAt: LocalDateTime? = null,

    @Column(name = "started_at")
    var startedAt: LocalDateTime? = null,

    @Column(name = "waiting_price", columnDefinition = "double precision default 0.0")
    var waitingPrice: Double = 0.0,

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

    @Column(name = "cancellation_reason")
    var cancellationReason: String? = null,

    @Column(name = "tariff_name")
    var tariffName: String? = null,
    
    @Column(name = "payment_method")
    var paymentMethod: String = "CASH",

    @Column(name = "assignment_type")
    var assignmentType: String? = "ETHER", // Возможные значения: CHAIN, HOME, CYCLE, AUTO, ETHER

    @Column(nullable = false)
    var addedValue: Double = 0.0,

    @Column(name = "commission_amount", nullable = false, columnDefinition = "double precision default 0.0")
    var commissionAmount: Double = 0.0,
    
    @Column(name = "authorized_amount", nullable = false, columnDefinition = "double precision default 0.0")
    var authorizedAmount: Double = 0.0,

    // СРАЗУ ПОСЛЕ НЕГО ДОБАВЛЯЕШЬ ЭТИ ДВА ПОЛЯ:
    @Column(name = "bank_commission_amount", nullable = false, columnDefinition = "double precision default 0.0")
    var bankCommissionAmount: Double = 0.0,

    @Column(name = "payout_amount", nullable = false, columnDefinition = "double precision default 0.0")
    var payoutAmount: Double = 0.0,

    @Column(nullable = false)
    var isRatedByClient: Boolean = false,

    @Column(nullable = false)
    var isRatedByDriver: Boolean = false,

    @ManyToMany(fetch = FetchType.EAGER)
    @Fetch(FetchMode.SUBSELECT) // <-- Защита от MultipleBagFetchException и Cartesian Product
    @JoinTable(
        name = "order_services_link",
        joinColumns = [JoinColumn(name = "order_id")],
        inverseJoinColumns = [JoinColumn(name = "service_id")]
    )
    val selectedServices: MutableList<TaxiServiceEntity> = mutableListOf()
) {
    @OneToMany(mappedBy = "order", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.EAGER)
    @Fetch(FetchMode.SUBSELECT) // <-- Защита от MultipleBagFetchException и Cartesian Product
    var stops: MutableList<OrderStop> = mutableListOf()
    
    @Column(name = "current_stop_order", nullable = false, columnDefinition = "integer default 0")
    var currentStopOrder: Int = 0

    @Column(name = "waypoint_arrived_at")
    var waypointArrivedAt: LocalDateTime? = null
    
    protected constructor() : this(
        client = Client(), 
        fromAddress = "",
        toAddress = "",
        tariff = CarTariff(name = "Stub", basePrice = 0.0, pricePerKm = 0.0) 
    )
}