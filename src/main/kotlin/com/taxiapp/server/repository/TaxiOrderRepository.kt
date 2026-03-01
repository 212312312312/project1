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

@Repository
interface TaxiOrderRepository : JpaRepository<TaxiOrder, Long> {

    fun findAllByClient(client: Client): List<TaxiOrder>

    @Query("SELECT o FROM TaxiOrder o WHERE o.status IN (:statuses) ORDER BY o.createdAt DESC")
    fun findActiveOrders(statuses: List<OrderStatus> = listOf(
        OrderStatus.REQUESTED, 
        OrderStatus.ACCEPTED, 
        OrderStatus.IN_PROGRESS,
        OrderStatus.SCHEDULED
    )): List<TaxiOrder>

    @Query("SELECT o FROM TaxiOrder o WHERE o.status = 'SCHEDULED' AND o.driver IS NULL ORDER BY o.scheduledAt ASC")
    fun findAllScheduledOrders(): List<TaxiOrder>

    // --- НОВИЙ МЕТОД ДЛЯ ЕФІРУ ---
    // Вибирає REQUESTED або SCHEDULED, але тільки ті, де немає водія
    @Query("SELECT o FROM TaxiOrder o WHERE o.status = 'REQUESTED' OR (o.status = 'SCHEDULED' AND o.driver IS NULL)")
    fun findAllAvailableForEther(): List<TaxiOrder>
    // ----------------------------

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

    fun findAllByStatusIn(statuses: List<OrderStatus>): List<TaxiOrder>

    fun findAllByClientId(clientId: Long): List<TaxiOrder>

    fun findAllByDriverId(driverId: Long): List<TaxiOrder>

    fun findAllByClientOrderByCreatedAtDesc(client: Client): List<TaxiOrder>

    fun findAllByStatusAndOfferExpiresAtBefore(status: OrderStatus, time: LocalDateTime): List<TaxiOrder>

    fun findAllByStatusAndScheduledAtBefore(status: OrderStatus, time: LocalDateTime): List<TaxiOrder>

    // Підрахунок кількості активних замовлень конкретного клієнта
    fun countByClientIdAndStatusIn(clientId: Long, statuses: List<OrderStatus>): Int
    
    @Modifying
    @Transactional
    @Query("UPDATE TaxiOrder o SET o.destinationSector = null WHERE o.destinationSector.id = :sectorId")
    fun clearSectorReference(@Param("sectorId") sectorId: Long)

    @Query("SELECT o FROM TaxiOrder o WHERE o.driver.id = :driverId AND o.status IN ('ACCEPTED', 'ARRIVED', 'IN_PROGRESS')")
    fun findActiveOrderByDriverId(@Param("driverId") driverId: Long): Optional<TaxiOrder>

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
}