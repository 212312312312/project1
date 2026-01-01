package com.taxiapp.server.repository

import com.taxiapp.server.model.enums.OrderStatus
import com.taxiapp.server.model.order.TaxiOrder
import com.taxiapp.server.model.user.Client // <-- ДОБАВЛЕН ИМПОРТ
import com.taxiapp.server.model.user.Driver
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface TaxiOrderRepository : JpaRepository<TaxiOrder, Long> {

    fun findAllByClient(client: Client): List<TaxiOrder>

    @Query("SELECT o FROM TaxiOrder o WHERE o.status IN (:statuses) ORDER BY o.createdAt DESC")
    fun findActiveOrders(statuses: List<OrderStatus> = listOf(
        OrderStatus.REQUESTED, 
        OrderStatus.ACCEPTED, 
        OrderStatus.IN_PROGRESS
    )): List<TaxiOrder>

    fun findByDriverAndStatusIn(driver: Driver, statuses: List<OrderStatus>): TaxiOrder?

    @Query("SELECT o FROM TaxiOrder o WHERE o.status IN (:statuses) ORDER BY o.completedAt DESC")
    fun findArchivedOrders(statuses: List<OrderStatus> = listOf(
        OrderStatus.COMPLETED, 
        OrderStatus.CANCELLED
    )): List<TaxiOrder>

    @Query("SELECT o FROM TaxiOrder o WHERE o.client.userPhone LIKE %:phone% OR o.driver.userPhone LIKE %:phone%")
    fun searchArchiveByPhone(phone: String): List<TaxiOrder>
    
    fun findAllByDriverAndStatusIn(driver: Driver, statuses: List<OrderStatus>): List<TaxiOrder>

    fun findAllByStatus(status: OrderStatus): List<TaxiOrder>

    fun findAllByClientId(clientId: Long): List<TaxiOrder>

    // Теперь Client распознается благодаря импорту
    fun findAllByClientOrderByCreatedAtDesc(client: Client): List<TaxiOrder>
}