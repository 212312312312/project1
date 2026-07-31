package com.taxiapp.server.service

import com.taxiapp.server.dto.order.TaxiOrderDto
import com.taxiapp.server.model.enums.OrderStatus
import com.taxiapp.server.repository.DriverRepository
import com.taxiapp.server.repository.TaxiOrderRepository
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Lazy
import org.springframework.http.HttpStatus
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime

@Service
class OrderAdminService(
    private val orderRepository: TaxiOrderRepository,
    private val driverRepository: DriverRepository,
    private val evoSService: EvoSService,
    private val notificationService: NotificationService,
    private val messagingTemplate: SimpMessagingTemplate,
    @Lazy private val orderService: OrderService
) {

    private val logger = LoggerFactory.getLogger(OrderAdminService::class.java)

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

        // 1. Отмена заказа у партнеров (EvoS), если он туда транслировался
        val evosUid = order.evosOrderUid
        if (order.isSentToEvos && !evosUid.isNullOrEmpty()) {
            val isCanceledInEvos = evoSService.cancelOrderInEvoS(evosUid)
            logger.info(">>> [AdminCancel] Отмена заказа $orderId в EvoS (UID: $evosUid). Результат: $isCanceledInEvos")
            order.isSentToEvos = false
            order.evosOrderUid = null
        }

        // 2. Обновляем статус заказа
        order.status = OrderStatus.CANCELLED
        order.cancellationReason = "Скасовано диспетчером"
        order.completedAt = LocalDateTime.now() 
        
        val updatedOrder = orderRepository.save(order)
        val dto = TaxiOrderDto(updatedOrder)

        // 3. 🟢 WEBSOCKET ПАССАЖИРУ: транслируем отмену на персональный топик заказа
        messagingTemplate.convertAndSend("/topic/orders/${updatedOrder.id}", dto)

        // 4. 🟢 WEBSOCKET ДИСПЕТЧЕРУ И ЭФИРУ: убираем из активных списков
        orderService.broadcastOrderChange(updatedOrder, "REMOVE")

        // 5. 🟢 FCM PUSH ПАССАЖИРУ: мгновенно закрывает UI приложения
        if (!updatedOrder.client.fcmToken.isNullOrBlank()) {
            notificationService.sendOrderStatusToClient(
                token = updatedOrder.client.fcmToken,
                orderId = updatedOrder.id!!,
                status = OrderStatus.CANCELLED.name,
                title = "Замовлення скасовано",
                body = "Диспетчер скасував замовлення."
            )
        }

        return dto
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

        // Если диспетчер вручную назначает нашего водителя — снимаем заказ у партнеров
        val evosUid = order.evosOrderUid
        if (order.isSentToEvos && !evosUid.isNullOrEmpty()) {
            evoSService.cancelOrderInEvoS(evosUid)
            order.isSentToEvos = false
            order.evosOrderUid = null
        }

        order.driver = driver
        order.status = OrderStatus.ACCEPTED 

        val updatedOrder = orderRepository.save(order)
        val dto = TaxiOrderDto(updatedOrder)

        // 1. 🟢 WEBSOCKET ПАССАЖИРУ: обновляем состояние поездки на экране приложения
        messagingTemplate.convertAndSend("/topic/orders/${updatedOrder.id}", dto)
        
        // 2. 🟢 WEBSOCKET ДИСПЕТЧЕРУ И ЭФИРУ
        orderService.broadcastOrderChange(updatedOrder, "UPDATE")

        // 3. 🟢 FCM PUSH ПАССАЖИРУ: оповещаем о назначении водителя
        if (!updatedOrder.client.fcmToken.isNullOrBlank()) {
            notificationService.sendOrderStatusToClient(
                token = updatedOrder.client.fcmToken,
                orderId = updatedOrder.id!!,
                status = OrderStatus.ACCEPTED.name,
                title = "Водія призначено",
                body = "Диспетчер призначив водія ${driver.fullName}. Авто прямує до вас."
            )
        }

        return dto
    }
}