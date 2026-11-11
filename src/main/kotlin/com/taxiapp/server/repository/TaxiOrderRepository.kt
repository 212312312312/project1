package com.taxiapp.server.repository

import com.taxiapp.server.model.enums.OrderStatus
import com.taxiapp.server.model.order.TaxiOrder
import com.taxiapp.server.model.user.Client
import com.taxiapp.server.model.user.Driver
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.Optional
import java.util.UUID

@Repository
interface TaxiOrderRepository : JpaRepository<TaxiOrder, Long> {

    // --- МЕТОД ДЛЯ НАХОЖДЕНИЯ ЗАКАЗА ПО ПУБЛИЧНОМУ UUID ---
    @Query("""
        SELECT o FROM TaxiOrder o
        LEFT JOIN FETCH o.client
        LEFT JOIN FETCH o.tariff
        LEFT JOIN FETCH o.originSector
        LEFT JOIN FETCH o.destinationSector
        LEFT JOIN FETCH o.driver d
        LEFT JOIN FETCH d.car
        LEFT JOIN FETCH o.offeredDriver
        WHERE o.uuid = :uuid
    """)
    fun findByUuid(@Param("uuid") uuid: UUID): Optional<TaxiOrder>

    // --- ПЕССИМИСТИЧЕСКАЯ БЛОКИРОВКА СТРОКИ ЗАКАЗА (SELECT ... FOR UPDATE) ---
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM TaxiOrder o WHERE o.id = :id")
    fun findByIdWithLock(@Param("id") id: Long): Optional<TaxiOrder>

    fun findAllByClient(client: Client): List<TaxiOrder>

    @Query("SELECT COUNT(o) FROM TaxiOrder o WHERE o.client.id = :clientId AND o.status IN :statuses")
    fun countActiveOrdersByClient(
        @Param("clientId") clientId: Long,
        @Param("statuses") statuses: List<OrderStatus>
    ): Int

    @Query("SELECT o FROM TaxiOrder o WHERE o.status IN (:statuses) ORDER BY o.createdAt DESC")
    fun findActiveOrders(statuses: List<OrderStatus> = listOf(
        OrderStatus.REQUESTED,
        OrderStatus.ACCEPTED,
        OrderStatus.IN_PROGRESS,
        OrderStatus.SCHEDULED
    )): List<TaxiOrder>

    @Query("SELECT o FROM TaxiOrder o WHERE o.status = 'SCHEDULED' AND o.driver IS NULL ORDER BY o.scheduledAt ASC")
    fun findAllScheduledOrders(): List<TaxiOrder>

    // HIGH-LOAD FIX: Загружаем заказы для Эфира за 1 SQL-запрос вместе со связанными LAZY-сущностями (устраняет N+1)
    @Query("""
        SELECT DISTINCT o FROM TaxiOrder o
        LEFT JOIN FETCH o.client
        LEFT JOIN FETCH o.tariff
        LEFT JOIN FETCH o.originSector
        LEFT JOIN FETCH o.destinationSector
        WHERE o.status = 'REQUESTED' OR (o.status = 'SCHEDULED' AND o.driver IS NULL AND o.offeredDriver IS NULL)
        ORDER BY o.id DESC
    """)
    fun findAllAvailableForEther(): List<TaxiOrder>

    fun findByDriverAndStatusIn(driver: Driver, statuses: List<OrderStatus>): TaxiOrder?

    @Query("SELECT o FROM TaxiOrder o WHERE o.status IN (:statuses) ORDER BY o.completedAt DESC")
    fun findArchivedOrders(statuses: List<OrderStatus> = listOf(
        OrderStatus.COMPLETED,
        OrderStatus.CANCELLED
    )): List<TaxiOrder>

    @Query("SELECT o FROM TaxiOrder o WHERE o.client.userPhone LIKE %:phone% OR o.driver.userPhone LIKE %:phone%")
    fun searchArchiveByPhone(@Param("phone") phone: String): List<TaxiOrder>

    fun findAllByDriverAndStatusIn(driver: Driver, statuses: List<OrderStatus>): List<TaxiOrder>

    fun findAllByStatus(status: OrderStatus): List<TaxiOrder>

    fun findAllByStatusInOrderByIdDesc(statuses: List<OrderStatus>): List<TaxiOrder>

    @Query("""
        SELECT DISTINCT o FROM TaxiOrder o
        LEFT JOIN FETCH o.client
        LEFT JOIN FETCH o.tariff
        LEFT JOIN FETCH o.originSector
        LEFT JOIN FETCH o.destinationSector
        LEFT JOIN FETCH o.driver d
        LEFT JOIN FETCH d.car
        WHERE o.status IN :statuses
    """)
    fun findAllByStatusIn(@Param("statuses") statuses: List<OrderStatus>): List<TaxiOrder>

    fun findAllByClientId(clientId: Long): List<TaxiOrder>

    fun findAllByStatusAndCreatedAtBefore(status: OrderStatus, threshold: LocalDateTime): List<TaxiOrder>

    fun findAllByDriverId(driverId: Long): List<TaxiOrder>

    fun findAllByClientOrderByCreatedAtDesc(client: Client): List<TaxiOrder>

    fun findAllByStatusAndOfferExpiresAtBefore(status: OrderStatus, time: LocalDateTime): List<TaxiOrder>

    fun findAllByStatusAndScheduledAtBefore(status: OrderStatus, time: LocalDateTime): List<TaxiOrder>

    fun countByClientIdAndStatusIn(clientId: Long, statuses: List<OrderStatus>): Int

    fun findAllByEvosCancelPendingTrue(): List<TaxiOrder>

    @Query("""
        SELECT DISTINCT o FROM TaxiOrder o
        LEFT JOIN FETCH o.client
        LEFT JOIN FETCH o.tariff
        LEFT JOIN FETCH o.originSector
        LEFT JOIN FETCH o.destinationSector
        LEFT JOIN FETCH o.driver d
        LEFT JOIN FETCH d.car
        WHERE o.client.id = :clientId
           OR (o.client.userPhone = :phone AND :phone IS NOT NULL AND :phone != '')
        ORDER BY o.id DESC
    """)
    fun findAllOrdersForClientHistory(
        @Param("clientId") clientId: Long,
        @Param("phone") phone: String?,
        pageable: Pageable
    ): List<TaxiOrder>

    fun findAllByStatusInAndIsSentToEvosFalseAndCreatedAtBefore(
        statuses: List<OrderStatus>,
        thresholdTime: LocalDateTime
    ): List<TaxiOrder>

    // Перевірка, чи є у клієнта активні (незавершені) замовлення зі знижкою
    @Query("""
        SELECT COUNT(o) > 0 
        FROM TaxiOrder o 
        WHERE o.client.id = :clientId 
          AND o.status NOT IN (:closedStatuses) 
          AND o.appliedDiscount > 0
    """)
    fun hasActiveOrderWithDiscount(
        @Param("clientId") clientId: Long,
        @Param("closedStatuses") closedStatuses: List<OrderStatus> = listOf(OrderStatus.COMPLETED, OrderStatus.CANCELLED)
    ): Boolean

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("""
        UPDATE TaxiOrder o 
        SET o.destinationSector = null, o.originSector = null 
        WHERE o.destinationSector.id = :sectorId OR o.originSector.id = :sectorId
    """)
    fun clearSectorReference(@Param("sectorId") sectorId: Long)

    @Query("""
        SELECT o.cancellationReason AS reason, COUNT(o.id) AS count 
        FROM TaxiOrder o 
        WHERE o.status = 'CANCELLED' AND o.cancellationReason IS NOT NULL 
        GROUP BY o.cancellationReason 
        ORDER BY count DESC
    """)
    fun getCancellationStats(): List<CancellationStatProjection>

    @Query("""
        SELECT o FROM TaxiOrder o
        LEFT JOIN FETCH o.client
        LEFT JOIN FETCH o.tariff
        LEFT JOIN FETCH o.originSector
        LEFT JOIN FETCH o.destinationSector
        LEFT JOIN FETCH o.driver d
        LEFT JOIN FETCH d.car
        WHERE o.driver.id = :driverId
          AND o.status IN ('ACCEPTED', 'DRIVER_ARRIVED', 'ARRIVED_AT_WAYPOINT', 'IN_PROGRESS')
        ORDER BY o.id DESC
    """)
    fun findActiveOrderByDriverId(@Param("driverId") driverId: Long): Optional<TaxiOrder>

    // --- HIGH-LOAD ОПТИМИЗАЦИИ ДЛЯ ВОДИТЕЛЯ И ДИСПЕТЧЕРИЗАЦИИ ---

    @Query("""
        SELECT o FROM TaxiOrder o
        LEFT JOIN FETCH o.client
        LEFT JOIN FETCH o.tariff
        LEFT JOIN FETCH o.originSector
        LEFT JOIN FETCH o.destinationSector
        LEFT JOIN FETCH o.driver d
        LEFT JOIN FETCH d.car
        WHERE o.driver.id = :driverId
          AND o.status IN ('ACCEPTED', 'DRIVER_ARRIVED', 'ARRIVED_AT_WAYPOINT', 'IN_PROGRESS', 'SCHEDULED')
        ORDER BY CASE WHEN o.status = 'SCHEDULED' THEN 1 ELSE 0 END ASC, o.id DESC
    """)
    fun findActiveOrScheduledOrdersByDriverId(@Param("driverId") driverId: Long): List<TaxiOrder>

    @Query("""
        SELECT o FROM TaxiOrder o
        LEFT JOIN FETCH o.client
        LEFT JOIN FETCH o.tariff
        LEFT JOIN FETCH o.originSector
        LEFT JOIN FETCH o.destinationSector
        LEFT JOIN FETCH o.offeredDriver od
        LEFT JOIN FETCH od.car
        WHERE o.offeredDriver.id = :driverId
          AND o.status = 'OFFERING'
        ORDER BY o.id DESC
    """)
    fun findOfferingOrdersByDriverId(@Param("driverId") driverId: Long): List<TaxiOrder>

    @Query("""
        SELECT o FROM TaxiOrder o
        LEFT JOIN FETCH o.client
        LEFT JOIN FETCH o.tariff
        LEFT JOIN FETCH o.originSector
        LEFT JOIN FETCH o.destinationSector
        LEFT JOIN FETCH o.driver d
        LEFT JOIN FETCH d.car
        WHERE o.driver.id = :driverId
          AND o.status IN ('COMPLETED', 'CANCELLED')
        ORDER BY o.id DESC
    """)
    fun findDriverHistoryOrders(
        @Param("driverId") driverId: Long,
        pageable: Pageable
    ): List<TaxiOrder>

    @Query("""
        SELECT DISTINCT o FROM TaxiOrder o
        JOIN FETCH o.driver d
        LEFT JOIN FETCH d.car
        LEFT JOIN FETCH d.selectedTariffs
        LEFT JOIN FETCH d.allowedTariffs
        WHERE o.status = 'IN_PROGRESS'
          AND o.destLat BETWEEN :minLat AND :maxLat
          AND o.destLng BETWEEN :minLng AND :maxLng
    """)
    fun findChainCandidateOrdersInBounds(
        @Param("minLat") minLat: Double,
        @Param("maxLat") maxLat: Double,
        @Param("minLng") minLng: Double,
        @Param("maxLng") maxLng: Double
    ): List<TaxiOrder>

    @Query("""
        SELECT DISTINCT o.offeredDriver.id FROM TaxiOrder o
        WHERE o.status = 'OFFERING' AND o.offeredDriver IS NOT NULL
    """)
    fun findDriverIdsWithActiveOffers(): Set<Long>

    @Query("""
        SELECT DISTINCT o.driver.id FROM TaxiOrder o
        WHERE o.status IN ('ACCEPTED', 'DRIVER_ARRIVED', 'IN_PROGRESS', 'ARRIVED_AT_WAYPOINT')
          AND o.driver IS NOT NULL
    """)
    fun findDriverIdsWithActiveOrders(): Set<Long>

    @Query("""
        SELECT DISTINCT d FROM Driver d
        LEFT JOIN FETCH d.car
        LEFT JOIN FETCH d.selectedTariffs
        LEFT JOIN FETCH d.allowedTariffs
        LEFT JOIN FETCH d.homeSectors
        WHERE d.isOnline = true
          AND d.isBlocked = false
          AND d.latitude BETWEEN :minLat AND :maxLat
          AND d.longitude BETWEEN :minLng AND :maxLng
    """)
    fun findOnlineDriversInBounds(
        @Param("minLat") minLat: Double,
        @Param("maxLat") maxLat: Double,
        @Param("minLng") minLng: Double,
        @Param("maxLng") maxLng: Double
    ): List<Driver>

    // --- МЕТОДЫ ДЛЯ ОПТИМИЗАЦИИ EVOS SCHEDULER ---
    @Query("SELECT o FROM TaxiOrder o WHERE o.isSentToEvos = false AND o.status = :status AND o.createdAt < :threshold")
    fun findAllByStatusAndIsSentToEvosFalseAndCreatedAtBefore(
        @Param("status") status: OrderStatus,
        @Param("threshold") threshold: LocalDateTime
    ): List<TaxiOrder>

    @Query("SELECT o FROM TaxiOrder o WHERE o.isSentToEvos = true AND o.status NOT IN :statuses")
    fun findAllByIsSentToEvosTrueAndStatusNotIn(
        @Param("statuses") statuses: List<OrderStatus>
    ): List<TaxiOrder>

    @Query("""
        SELECT o FROM TaxiOrder o 
        WHERE o.driver.id = :driverId 
        AND o.status = 'COMPLETED' 
        AND o.completedAt BETWEEN :start AND :end
    """)
    fun findCompletedOrdersForStats(
        @Param("driverId") driverId: Long,
        @Param("start") start: LocalDateTime,
        @Param("end") end: LocalDateTime
    ): List<TaxiOrder>

    @Query("SELECT o FROM TaxiOrder o WHERE o.client.id = :clientId AND o.status IN :statuses ORDER BY o.createdAt DESC")
    fun findArchivedOrdersByClientId(
        @Param("clientId") clientId: Long,
        @Param("statuses") statuses: List<OrderStatus>,
        pageable: Pageable
    ): List<TaxiOrder>

    // =========================================================================
    // БАЗОВЫЕ МЕТОДЫ АНАЛИТИКИ (С ИСПРАВЛЕННЫМИ ФОРМУЛАМИ)
    // =========================================================================

    @Query("SELECT AVG(o.price) FROM TaxiOrder o WHERE o.status = 'COMPLETED'")
    fun calculateAverageOrderValue(): Double?

    @Query("SELECT SUM(o.price) FROM TaxiOrder o WHERE o.status = 'COMPLETED'")
    fun calculateTotalRevenue(): Double?

    fun countByStatus(status: OrderStatus): Long

    @Query("SELECT o.tariffName, COUNT(o), SUM(o.price) FROM TaxiOrder o WHERE o.status = 'COMPLETED' GROUP BY o.tariffName")
    fun getTariffAnalytics(): List<Array<Any>>

    @Query("SELECT COUNT(DISTINCT o.client.id) FROM TaxiOrder o WHERE o.status = 'COMPLETED' AND o.client.id <> 999999")
    fun countUniqueClientsWithOrders(): Long

    @Query("""
        SELECT o.cancellationReason AS reason, COUNT(o.id) AS count 
        FROM TaxiOrder o 
        WHERE o.status = 'CANCELLED' 
          AND o.cancellationReason IS NOT NULL 
          AND o.cancellationReason IN (SELECT cr.reasonText FROM CancellationReason cr WHERE cr.target = 'CLIENT')
        GROUP BY o.cancellationReason 
        ORDER BY count DESC
    """)
    fun getClientCancellationStats(): List<CancellationStatProjection>

    @Query(value = """
        SELECT COALESCE(AVG(EXTRACT(EPOCH FROM (o.accepted_at - o.created_at))), 0.0) 
        FROM taxi_orders o 
        WHERE o.accepted_at IS NOT NULL 
          AND o.status = 'COMPLETED'
          AND o.scheduled_at IS NULL
          AND o.accepted_at >= o.created_at
    """, nativeQuery = true)
    fun calculateAvgTimeToAcceptSeconds(): Double

    @Query("SELECT COUNT(o) FROM TaxiOrder o WHERE o.boostAdded > 0")
    fun countOrdersWithBoost(): Long

    @Query("SELECT COUNT(o) FROM TaxiOrder o WHERE o.boostAdded > 0 AND o.status = 'COMPLETED'")
    fun countCompletedOrdersWithBoost(): Long

    @Query(value = """
        SELECT COUNT(o.id) 
        FROM taxi_orders o 
        WHERE o.status = 'CANCELLED' 
          AND o.scheduled_at IS NULL
          AND o.cancelled_at IS NOT NULL 
          AND EXTRACT(EPOCH FROM (o.cancelled_at - o.created_at)) BETWEEN 0 AND 60
    """, nativeQuery = true)
    fun countQuickClientCancellations(): Long

    @Query(value = """
        SELECT COUNT(o.id) 
        FROM taxi_orders o 
        WHERE o.scheduled_at IS NULL
          AND o.accepted_at IS NULL
          AND (
              (o.status = 'CANCELLED' AND o.cancelled_at IS NOT NULL AND EXTRACT(EPOCH FROM (o.cancelled_at - o.created_at)) > 180)
              OR (o.status IN ('REQUESTED', 'OFFERING') AND EXTRACT(EPOCH FROM (NOW() - o.created_at)) > 180)
          )
    """, nativeQuery = true)
    fun countTimeoutCancellations(): Long

    @Query(value = """
        WITH client_nth_ride AS (
            SELECT client_id, created_at,
                   ROW_NUMBER() OVER (PARTITION BY client_id ORDER BY created_at ASC) as rn
            FROM taxi_orders
            WHERE status = 'COMPLETED' AND client_id <> 999999
        ),
        client_first_ride AS (
            SELECT client_id, created_at as first_ride_at
            FROM client_nth_ride
            WHERE rn = 1
        )
        SELECT COALESCE(AVG(EXTRACT(EPOCH FROM (rn_table.created_at - f.first_ride_at)) / 86400.0), 0.0)
        FROM client_nth_ride rn_table
        JOIN client_first_ride f ON rn_table.client_id = f.client_id
        WHERE rn_table.rn = 8
    """, nativeQuery = true)
    fun calculateAvgDaysTo8thRide(): Double

    @Query(value = """
        WITH client_nth_ride AS (
            SELECT client_id, created_at,
                   ROW_NUMBER() OVER (PARTITION BY client_id ORDER BY created_at ASC) as rn
            FROM taxi_orders
            WHERE status = 'COMPLETED' AND client_id <> 999999
        ),
        client_first_ride AS (
            SELECT client_id, created_at as first_ride_at
            FROM client_nth_ride
            WHERE rn = 1
        )
        SELECT COALESCE(AVG(EXTRACT(EPOCH FROM (rn_table.created_at - f.first_ride_at)) / 86400.0), 0.0)
        FROM client_nth_ride rn_table
        JOIN client_first_ride f ON rn_table.client_id = f.client_id
        WHERE rn_table.rn = :targetRides
    """, nativeQuery = true)
    fun calculateAvgDaysToTargetRide(@Param("targetRides") targetRides: Int): Double

    @Query(value = """
        SELECT 
            COUNT(o.id) AS totalOrders,
            COUNT(CASE WHEN o.status = 'COMPLETED' THEN 1 END) AS completedOrders,
            COUNT(CASE WHEN o.status = 'CANCELLED' THEN 1 END) AS cancelledOrders,
            COUNT(DISTINCT CASE WHEN o.status = 'COMPLETED' AND o.client_id <> 999999 THEN o.client_id END) AS uniquePayingClients,
            COALESCE(SUM(CASE WHEN o.status = 'COMPLETED' THEN o.price ELSE 0.0 END), 0.0) AS gmv,
            COALESCE(AVG(CASE WHEN o.status = 'COMPLETED' THEN o.price END), 0.0) AS avgGrossCheck,
            COALESCE(SUM(CASE WHEN o.status = 'COMPLETED' THEN o.commission_amount ELSE 0.0 END), 0.0) AS totalCommission,
            COALESCE(SUM(CASE WHEN o.status = 'COMPLETED' THEN o.applied_discount ELSE 0.0 END), 0.0) AS totalDiscounts,
            COALESCE(SUM(CASE WHEN o.status = 'COMPLETED' THEN o.waiting_price ELSE 0.0 END), 0.0) AS totalWaiting,
            COUNT(CASE WHEN o.status = 'COMPLETED' AND o.applied_discount > 0 THEN 1 END) AS discountedCompletedCount
        FROM taxi_orders o
        WHERE o.created_at BETWEEN :startDate AND :endDate
    """, nativeQuery = true)
    fun getPeriodSummaryMetrics(
        @Param("startDate") startDate: LocalDateTime,
        @Param("endDate") endDate: LocalDateTime
    ): List<Array<Any>>

    @Query(value = """
        SELECT 
            COALESCE(o.tariff_name, 'Невідомий тариф') AS tariffName,
            COUNT(CASE WHEN o.status = 'COMPLETED' THEN 1 END) AS completedCount,
            COUNT(CASE WHEN o.status = 'CANCELLED' THEN 1 END) AS cancelledCount,
            COALESCE(SUM(CASE WHEN o.status = 'COMPLETED' THEN o.price ELSE 0.0 END), 0.0) AS totalRevenue,
            COALESCE(AVG(CASE WHEN o.status = 'COMPLETED' THEN o.price END), 0.0) AS avgCheck,
            COALESCE(SUM(CASE WHEN o.status = 'COMPLETED' THEN o.commission_amount ELSE 0.0 END), 0.0) AS totalCommission,
            COALESCE(SUM(CASE WHEN o.status = 'COMPLETED' THEN o.applied_discount ELSE 0.0 END), 0.0) AS totalDiscounts
        FROM taxi_orders o
        WHERE o.created_at BETWEEN :startDate AND :endDate
        GROUP BY COALESCE(o.tariff_name, 'Невідомий тариф')
        ORDER BY totalRevenue DESC
    """, nativeQuery = true)
    fun getTariffAnalyticsByPeriod(
        @Param("startDate") startDate: LocalDateTime,
        @Param("endDate") endDate: LocalDateTime
    ): List<Array<Any>>

    @Query(value = """
        SELECT o.cancellation_reason AS reason, COUNT(o.id) AS count
        FROM taxi_orders o
        WHERE o.status = 'CANCELLED'
          AND o.created_at BETWEEN :startDate AND :endDate
          AND o.cancellation_reason IS NOT NULL
          AND o.cancellation_reason <> ''
        GROUP BY o.cancellation_reason
        ORDER BY count DESC
    """, nativeQuery = true)
    fun getCancellationStatsByPeriod(
        @Param("startDate") startDate: LocalDateTime,
        @Param("endDate") endDate: LocalDateTime
    ): List<CancellationStatProjection>

    @Query(value = """
        SELECT 
            COALESCE(AVG(CASE 
                WHEN o.scheduled_at IS NULL AND o.accepted_at IS NOT NULL AND o.accepted_at >= o.created_at 
                AND EXTRACT(EPOCH FROM (o.accepted_at - o.created_at)) <= 3600
                THEN EXTRACT(EPOCH FROM (o.accepted_at - o.created_at)) 
            END), 0.0) AS avgAcceptSec,
            COALESCE(AVG(CASE 
                WHEN o.accepted_at IS NOT NULL AND o.arrived_at IS NOT NULL AND o.arrived_at >= o.accepted_at 
                AND EXTRACT(EPOCH FROM (o.arrived_at - o.accepted_at)) <= 3600
                THEN EXTRACT(EPOCH FROM (o.arrived_at - o.accepted_at)) 
            END), 0.0) AS avgArriveSec,
            COALESCE(AVG(CASE 
                WHEN o.status = 'COMPLETED' AND o.duration_seconds IS NOT NULL AND o.duration_seconds > 0 
                THEN o.duration_seconds
                WHEN o.status = 'COMPLETED' AND o.started_at IS NOT NULL AND o.completed_at IS NOT NULL AND o.completed_at > o.started_at
                THEN EXTRACT(EPOCH FROM (o.completed_at - o.started_at))
            END), 0.0) AS avgTripDurationSec,
            COALESCE(AVG(CASE 
                WHEN o.status = 'COMPLETED' AND o.distance_meters IS NOT NULL AND o.distance_meters > 0 
                THEN o.distance_meters / 1000.0 
            END), 0.0) AS avgDistanceKm,
            COUNT(CASE WHEN o.boost_added > 0 OR o.added_value > 0 THEN 1 END) AS boostOrdersCount,
            COUNT(CASE WHEN (o.boost_added > 0 OR o.added_value > 0) AND o.status = 'COMPLETED' THEN 1 END) AS boostCompletedCount,
            COUNT(CASE WHEN o.scheduled_at IS NOT NULL THEN 1 END) AS scheduledOrdersCount,
            COUNT(CASE WHEN o.scheduled_at IS NOT NULL AND o.status = 'COMPLETED' THEN 1 END) AS scheduledCompletedCount
        FROM taxi_orders o
        WHERE o.created_at BETWEEN :startDate AND :endDate
    """, nativeQuery = true)
    fun getSlaAndBoostMetricsByPeriod(
        @Param("startDate") startDate: LocalDateTime,
        @Param("endDate") endDate: LocalDateTime
    ): List<Array<Any>>

    @Query(value = """
        SELECT 
            CAST(COALESCE(o.completed_at, o.created_at) AS DATE) AS orderDate,
            COUNT(o.id) AS completedOrders,
            COALESCE(SUM(GREATEST(o.price, o.final_price, o.base_price)), 0.0) AS gmv,
            COALESCE(SUM(
                CASE 
                    WHEN o.commission_amount > 0 THEN o.commission_amount
                    ELSE GREATEST(o.price, o.final_price, o.base_price, 100.0) * 0.10
                END
            ), 0.0) AS commission,
            COALESCE(SUM(o.applied_discount), 0.0) AS discounts
        FROM taxi_orders o
        WHERE o.status = 'COMPLETED'
          AND COALESCE(o.completed_at, o.created_at) BETWEEN :startDate AND :endDate
        GROUP BY CAST(COALESCE(o.completed_at, o.created_at) AS DATE)
        ORDER BY orderDate ASC
    """, nativeQuery = true)
    fun getDailyRevenueTimelineByPeriod(
        @Param("startDate") startDate: LocalDateTime,
        @Param("endDate") endDate: LocalDateTime
    ): List<Array<Any>>

    @Query(value = """
        SELECT 
            COUNT(CASE WHEN o.status = 'CANCELLED' THEN 1 END) AS totalCancelled,
            COUNT(CASE WHEN o.status = 'CANCELLED' AND o.accepted_at IS NULL THEN 1 END) AS cancelledBeforeAccept,
            COUNT(CASE WHEN o.status = 'CANCELLED' AND o.accepted_at IS NOT NULL THEN 1 END) AS cancelledAfterAccept,
            COUNT(CASE WHEN o.status = 'CANCELLED' AND (o.is_sent_to_evos = true AND LOWER(COALESCE(o.cancellation_reason, '')) LIKE '%партнер%') THEN 1 END) AS cancelledByEvos,
            COUNT(CASE WHEN o.status = 'CANCELLED' AND LOWER(COALESCE(o.cancellation_reason, '')) LIKE '%диспетчер%' THEN 1 END) AS cancelledByDispatcher,
            COUNT(CASE WHEN o.status = 'CANCELLED' AND o.scheduled_at IS NULL AND o.cancelled_at IS NOT NULL AND EXTRACT(EPOCH FROM (o.cancelled_at - o.created_at)) BETWEEN 0 AND 60 THEN 1 END) AS quickCancelUnder60s,
            COUNT(CASE WHEN o.scheduled_at IS NULL AND o.accepted_at IS NULL AND (
                (o.status = 'CANCELLED' AND o.cancelled_at IS NOT NULL AND EXTRACT(EPOCH FROM (o.cancelled_at - o.created_at)) > 180)
                OR (o.status IN ('REQUESTED', 'OFFERING') AND EXTRACT(EPOCH FROM (NOW() - o.created_at)) > 180)
            ) THEN 1 END) AS timeoutCancelOver180s
        FROM taxi_orders o
        WHERE o.created_at BETWEEN :startDate AND :endDate
    """, nativeQuery = true)
    fun getCancellationBreakdownByPeriod(
        @Param("startDate") startDate: LocalDateTime,
        @Param("endDate") endDate: LocalDateTime
    ): List<Array<Any>>

    @Query(value = """
        SELECT 
            COUNT(CASE WHEN o.status = 'COMPLETED' AND (o.is_evos_driver_assigned = false AND o.driver_id IS NOT NULL) THEN 1 END) AS ownFleetCompleted,
            COUNT(CASE WHEN o.status = 'COMPLETED' AND (o.is_evos_driver_assigned = true OR (o.is_sent_to_evos = true AND o.driver_id IS NULL)) THEN 1 END) AS evosCompleted,
            COUNT(CASE WHEN o.status = 'COMPLETED' AND o.assignment_type = 'CHAIN' THEN 1 END) AS chainCount,
            COUNT(CASE WHEN o.status = 'COMPLETED' AND o.assignment_type IN ('AUTO', 'CYCLE', 'HOME') THEN 1 END) AS autoFilterCount,
            COUNT(CASE WHEN o.status = 'COMPLETED' AND (o.assignment_type = 'ETHER' OR o.assignment_type IS NULL) AND o.is_evos_driver_assigned = false THEN 1 END) AS etherCount
        FROM taxi_orders o
        WHERE o.created_at BETWEEN :startDate AND :endDate
    """, nativeQuery = true)
    fun getFleetSplitByPeriod(
        @Param("startDate") startDate: LocalDateTime,
        @Param("endDate") endDate: LocalDateTime
    ): List<Array<Any>>

    @Query(value = """
        SELECT 
            CAST(EXTRACT(HOUR FROM o.created_at) AS INTEGER) AS hourOfDay,
            COUNT(o.id) AS totalOrders,
            COUNT(CASE WHEN o.status = 'COMPLETED' THEN 1 END) AS completedOrders,
            COUNT(CASE WHEN o.status = 'CANCELLED' THEN 1 END) AS cancelledOrders,
            COUNT(CASE WHEN o.boost_added > 0 THEN 1 END) AS boostOrders
        FROM taxi_orders o
        WHERE o.created_at BETWEEN :startDate AND :endDate
        GROUP BY CAST(EXTRACT(HOUR FROM o.created_at) AS INTEGER)
        ORDER BY hourOfDay ASC
    """, nativeQuery = true)
    fun getHourlyDemandByPeriod(
        @Param("startDate") startDate: LocalDateTime,
        @Param("endDate") endDate: LocalDateTime
    ): List<Array<Any>>

    @Query(value = """
        SELECT 
            s.name AS sectorName,
            COUNT(o.id) AS totalOrders,
            COUNT(CASE WHEN o.status = 'COMPLETED' THEN 1 END) AS completedOrders,
            COUNT(CASE WHEN o.status = 'CANCELLED' THEN 1 END) AS cancelledOrders,
            COALESCE(AVG(o.price), 0.0) AS avgPrice
        FROM taxi_orders o
        INNER JOIN sectors s ON o.origin_sector_id = s.id
        WHERE o.created_at BETWEEN :startDate AND :endDate
        GROUP BY s.id, s.name
        ORDER BY totalOrders DESC
        LIMIT 12
    """, nativeQuery = true)
    fun getSectorDemandByPeriod(
        @Param("startDate") startDate: LocalDateTime,
        @Param("endDate") endDate: LocalDateTime
    ): List<Array<Any>>

    @Query(value = """
        SELECT 
            o.client_id AS clientId,
            MIN(o.created_at) AS firstRideAt,
            COUNT(o.id) AS completedRidesCount
        FROM taxi_orders o
        WHERE o.status = 'COMPLETED'
          AND o.client_id <> 999999
        GROUP BY o.client_id
    """, nativeQuery = true)
    fun findClientCompletedOrderCohorts(): List<Array<Any>>

    @Query(value = """
        SELECT o.client_device_id 
        FROM taxi_orders o 
        WHERE o.client_id = :clientId 
          AND o.client_device_id IS NOT NULL 
          AND TRIM(o.client_device_id) <> ''
        ORDER BY o.id DESC 
        LIMIT 1
    """, nativeQuery = true)
    fun findLatestDeviceIdByClientId(@Param("clientId") clientId: Long): String?

    @Query(value = """
        SELECT o.to_address AS address,
               AVG(o.dest_lat) AS lat,
               AVG(o.dest_lng) AS lng,
               COUNT(o.id) AS ridesCount
        FROM taxi_orders o
        WHERE o.dest_lat BETWEEN :minLat AND :maxLat
          AND o.dest_lng BETWEEN :minLng AND :maxLng
          AND o.to_address IS NOT NULL
          AND LENGTH(TRIM(o.to_address)) > 3
          AND LOWER(o.to_address) NOT LIKE '%точка на карті%'
          AND LOWER(o.to_address) NOT LIKE '%point on map%'
          AND LOWER(o.to_address) NOT LIKE '%невідома адреса%'
        GROUP BY o.to_address
        ORDER BY ridesCount DESC
        LIMIT 30
    """, nativeQuery = true)
    fun findPopularDestinationsNearby(
        @Param("minLat") minLat: Double,
        @Param("maxLat") maxLat: Double,
        @Param("minLng") minLng: Double,
        @Param("maxLng") maxLng: Double
    ): List<PopularPlaceProjection>
}

interface CancellationStatProjection {
    val reason: String
    val count: Long
}

interface PopularPlaceProjection {
    val address: String
    val lat: Double
    val lng: Double
    val ridesCount: Long
}