package com.taxiapp.server.service

import com.taxiapp.server.dto.order.TaxiOrderDto
import com.taxiapp.server.model.enums.OrderStatus
import com.taxiapp.server.repository.DriverRepository
import com.taxiapp.server.repository.TaxiOrderRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime
import org.springframework.context.annotation.Lazy

@Service
class OrderAdminService(
    private val orderRepository: TaxiOrderRepository,
    private val driverRepository: DriverRepository,
    @Lazy private val orderService: OrderService // Добавили @Lazy, чтобы избежать круговой зависимости
) {

    @Transactional(readOnly = true)
    fun getActiveOrders(): List<TaxiOrderDto> {
        return orderRepository.findActiveOrders().map { TaxiOrderDto(it) }
    }

    @Transactional(readOnly = true)
    fun getArchivedOrders(): List<TaxiOrderDto> {
        return orderRepository.findArchivedOrders().map { TaxiOrderDto(it) }
    }

    @Transactional(readOnly = true)
    fun searchArchive(phone: String): List<TaxiOrderDto> {
        return orderRepository.searchArchiveByPhone(phone).map { TaxiOrderDto(it) }
    }

    @Transactional
    fun cancelOrder(orderId: Long): TaxiOrderDto {
        val order = orderRepository.findById(orderId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ $orderId не найден") }
        
        if (order.status == OrderStatus.COMPLETED) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Нельзя отменить уже выполненный заказ")
        }

        order.status = OrderStatus.CANCELLED
        order.completedAt = LocalDateTime.now() 
        
        val updatedOrder = orderRepository.save(order)

        // Оповещаем водителей через WebSocket
        orderService.broadcastOrderChange(updatedOrder, "REMOVE")

        return TaxiOrderDto(updatedOrder)
    }

    @Transactional
    fun assignDriverToOrder(orderId: Long, driverId: Long): TaxiOrderDto {
        val order = orderRepository.findById(orderId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ $orderId не найден") }
        
        if (order.status != OrderStatus.REQUESTED) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Назначить водителя можно только на заказ в статусе 'REQUESTED'")
        }
        
        val driver = driverRepository.findById(driverId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Водитель $driverId не найден") }

        if (!driver.isOnline) {
             throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Водитель (ID ${driver.id}) не в сети (OFFLINE)")
        }

        val activeStatuses = listOf(OrderStatus.ACCEPTED, OrderStatus.IN_PROGRESS)
        val existingOrder = orderRepository.findByDriverAndStatusIn(driver, activeStatuses)
        if (existingOrder != null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Водитель (ID ${driver.id}) уже выполняет заказ (ID ${existingOrder.id})")
        }

        order.driver = driver
        order.status = OrderStatus.ACCEPTED 

        val updatedOrder = orderRepository.save(order)
        
        // Удаляем из общего эфира, так как водитель назначен
        orderService.broadcastOrderChange(updatedOrder, "REMOVE")

        return TaxiOrderDto(updatedOrder)
    }
}