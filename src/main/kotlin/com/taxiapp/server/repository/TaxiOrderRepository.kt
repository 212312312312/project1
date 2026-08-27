package com.taxiapp.server.repository

import com.taxiapp.server.model.enums.OrderStatus
import com.taxiapp.server.model.order.TaxiOrder
import com.taxiapp.server.model.user.Client
import com.taxiapp.server.model.user.Driver
import org.springframework.data.jpa.repository.JpaRepository
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
    fun findByUuid(uuid: UUID): Optional<TaxiOrder>

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

    @Query("SELECT o FROM TaxiOrder o WHERE o.status = 'REQUESTED' OR (o.status = 'SCHEDULED' AND o.driver IS NULL)")
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

    // ➕ ДОБАВИТЬ ЭТОТ МЕТОД:
    fun findAllByStatusInOrderByIdDesc(statuses: List<OrderStatus>): List<TaxiOrder>

    fun findAllByStatusIn(statuses: List<OrderStatus>): List<TaxiOrder>

    fun findAllByClientId(clientId: Long): List<TaxiOrder>

    // ➕ Добавить в TaxiOrderRepository.kt:
fun findAllByStatusAndCreatedAtBefore(status: OrderStatus, threshold: LocalDateTime): List<TaxiOrder>

    fun findAllByDriverId(driverId: Long): List<TaxiOrder>

    fun findAllByClientOrderByCreatedAtDesc(client: Client): List<TaxiOrder>

    fun findAllByStatusAndOfferExpiresAtBefore(status: OrderStatus, time: LocalDateTime): List<TaxiOrder>

    fun findAllByStatusAndScheduledAtBefore(status: OrderStatus, time: LocalDateTime): List<TaxiOrder>

    fun countByClientIdAndStatusIn(clientId: Long, statuses: List<OrderStatus>): Int

    fun findAllByEvosCancelPendingTrue(): List<TaxiOrder>

    @Query("""
        SELECT DISTINCT o FROM TaxiOrder o 
        WHERE o.client.id = :clientId 
           OR (o.client.userPhone = :phone AND :phone IS NOT NULL AND :phone != '')
        ORDER BY o.id DESC
    """)
    fun findAllOrdersForClientHistory(
        @Param("clientId") clientId: Long,
        @Param("phone") phone: String?,
        pageable: org.springframework.data.domain.Pageable
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

    @Query("SELECT o FROM TaxiOrder o WHERE o.driver.id = :driverId AND o.status IN ('ACCEPTED', 'ARRIVED', 'IN_PROGRESS')")
    fun findActiveOrderByDriverId(@Param("driverId") driverId: Long): Optional<TaxiOrder>

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
    // ----------------------------------------------

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
        pageable: org.springframework.data.domain.Pageable
    ): List<TaxiOrder>

    @Query("SELECT AVG(o.price) FROM TaxiOrder o WHERE o.status = 'COMPLETED'")
    fun calculateAverageOrderValue(): Double?

    @Query("SELECT SUM(o.price) FROM TaxiOrder o WHERE o.status = 'COMPLETED'")
    fun calculateTotalRevenue(): Double?

    fun countByStatus(status: OrderStatus): Long
    
    @Query("SELECT o.tariffName, COUNT(o), SUM(o.price) FROM TaxiOrder o WHERE o.status = 'COMPLETED' GROUP BY o.tariffName")
    fun getTariffAnalytics(): List<Array<Any>>

    @Query("SELECT COUNT(DISTINCT o.client.id) FROM TaxiOrder o")
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



    // ➕ Аналитические агрегационные методы:

// 1. Среднее время поиска водителя (в секундах)
@Query(value = """
    SELECT COALESCE(AVG(EXTRACT(EPOCH FROM (o.accepted_at - o.created_at))), 0.0) 
    FROM taxi_orders o 
    WHERE o.accepted_at IS NOT NULL AND o.status = 'COMPLETED'
""", nativeQuery = true)
fun calculateAvgTimeToAcceptSeconds(): Double

// 2. Статистика эффективности кнопки Boost (+20/+40 грн)
@Query("SELECT COUNT(o) FROM TaxiOrder o WHERE o.boostAdded > 0")
fun countOrdersWithBoost(): Long

@Query("SELECT COUNT(o) FROM TaxiOrder o WHERE o.boostAdded > 0 AND o.status = 'COMPLETED'")
fun countCompletedOrdersWithBoost(): Long

// 3. Отмены клиентом до 60 секунд
@Query(value = """
    SELECT COUNT(o.id) 
    FROM taxi_orders o 
    WHERE o.status = 'CANCELLED' 
      AND o.cancelled_at IS NOT NULL 
      AND EXTRACT(EPOCH FROM (o.cancelled_at - o.created_at)) <= 60
""", nativeQuery = true)
fun countQuickClientCancellations(): Long

// 4. Отмены по таймауту поиска
@Query(value = """
    SELECT COUNT(o.id) 
    FROM taxi_orders o 
    WHERE (o.accepted_at IS NOT NULL AND EXTRACT(EPOCH FROM (o.accepted_at - o.created_at)) > 180)
       OR (o.status = 'CANCELLED' AND o.cancelled_at IS NOT NULL AND o.accepted_at IS NULL AND EXTRACT(EPOCH FROM (o.cancelled_at - o.created_at)) > 180)
       OR (o.status IN ('REQUESTED', 'OFFERING') AND EXTRACT(EPOCH FROM (NOW() - o.created_at)) > 180)
""", nativeQuery = true)
fun countTimeoutCancellations(): Long

// 5. Среднее число дней до совершения 8 поездки активным клиентом
@Query(value = """
    WITH client_8th_ride AS (
        SELECT client_id, created_at,
               ROW_NUMBER() OVER (PARTITION BY client_id ORDER BY created_at ASC) as rn
        FROM taxi_orders
        WHERE status = 'COMPLETED'
    ),
    client_first_ride AS (
        SELECT client_id, created_at as first_ride_at
        FROM client_8th_ride
        WHERE rn = 1
    )
    SELECT COALESCE(AVG(EXTRACT(EPOCH FROM (r8.created_at - f.first_ride_at)) / 86400.0), 0.0)
    FROM client_8th_ride r8
    JOIN client_first_ride f ON r8.client_id = f.client_id
    WHERE r8.rn = 8
""", nativeQuery = true)
fun calculateAvgDaysTo8thRide(): Double
}

interface CancellationStatProjection {
    val reason: String
    val count: Long
}