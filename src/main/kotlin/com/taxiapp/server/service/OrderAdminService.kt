package com.taxiapp.server.service

import com.taxiapp.server.dto.order.TaxiOrderDto
import com.taxiapp.server.model.enums.OrderStatus
import com.taxiapp.server.repository.DriverRepository
import com.taxiapp.server.repository.TaxiOrderRepository
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Lazy
import org.springframework.data.redis.core.RedisTemplate
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
    private val liqPayService: LiqPayService,
    private val redisTemplate: RedisTemplate<String, Any>,
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
        val archiveStatuses = listOf(OrderStatus.COMPLETED, OrderStatus.CANCELLED)
        return orderRepository.findAllByStatusInOrderByIdDesc(archiveStatuses)
            .map { TaxiOrderDto(it) }
    }

    @Transactional(readOnly = true)
    fun searchArchive(phone: String): List<TaxiOrderDto> {
        return orderRepository.searchArchiveByPhone(phone).map { TaxiOrderDto(it) }
    }

    @Transactional
    fun cancelOrder(orderId: Long): TaxiOrderDto {
        val order = orderRepository.findByIdWithLock(orderId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ $orderId не найден") }

        if (order.status == OrderStatus.COMPLETED || order.status == OrderStatus.CANCELLED) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Заказ уже завершен или отменен")
        }

        // 1. Отмена заказа у партнеров (EvoS), если он туда транслировался
        val evosUid = order.evosOrderUid
        if (order.isSentToEvos && !evosUid.isNullOrEmpty()) {
            val isCanceledInEvos = evoSService.cancelOrderInEvoS(evosUid)
            logger.info(">>> [AdminCancel] Отмена заказа $orderId в EvoS (UID: $evosUid). Результат: $isCanceledInEvos")
            if (isCanceledInEvos) {
                order.isSentToEvos = false
                order.evosOrderUid = null
            } else {
                order.evosCancelPending = true
            }
        }

        // 2. Размораживаем холд на карте клиента, если заказ был оплачен картой
        if (order.paymentMethod == "CARD" && order.authorizedAmount > 0.0) {
            liqPayService.voidFunds("ride_${order.id}")
            order.authorizedAmount = 0.0
        }

        // 3. Очищаем активные ключи в Redis
        redisTemplate.opsForSet().remove("client:active_orders:${order.client.id}", order.id.toString())
        redisTemplate.opsForZSet().remove("orders:expired_offers", order.id.toString())
        order.driver?.id?.let { drvId ->
            redisTemplate.opsForHash<String, Any>().delete("orders:active_drivers", drvId.toString())
        }

        // 4. Обновляем статус заказа
        order.status = OrderStatus.CANCELLED
        order.cancellationReason = "Скасовано диспетчером"
        order.completedAt = LocalDateTime.now()
        order.cancelledAt = LocalDateTime.now()

        val updatedOrder = orderRepository.save(order)
        val dto = TaxiOrderDto(updatedOrder)

        // 5. WEBSOCKET ПАССАЖИРУ, ВОДИТЕЛЮ И ДИСПЕТЧЕРУ
        messagingTemplate.convertAndSend("/topic/orders/${updatedOrder.id}", dto)
        orderService.broadcastOrderChange(updatedOrder, "REMOVE")

        // 6. FCM PUSH ПАССАЖИРУ И ВОДИТЕЛЮ
        if (!updatedOrder.client.fcmToken.isNullOrBlank()) {
            notificationService.sendOrderStatusToClient(
                token = updatedOrder.client.fcmToken,
                orderId = updatedOrder.id!!,
                status = OrderStatus.CANCELLED.name,
                title = "Замовлення скасовано",
                body = "Диспетчер скасував замовлення."
            )
        }

        updatedOrder.driver?.let { drv ->
            notificationService.sendOrderCancelToDriver(drv, updatedOrder)
        }

        return dto
    }

    @Transactional
    fun assignDriverToOrder(orderId: Long, driverId: Long): TaxiOrderDto {
        val order = orderRepository.findByIdWithLock(orderId)
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

        val evosUid = order.evosOrderUid
        if (order.isSentToEvos && !evosUid.isNullOrEmpty()) {
            val cancelled = evoSService.cancelOrderInEvoS(evosUid)
            if (cancelled) {
                order.isSentToEvos = false
                order.evosOrderUid = null
            } else {
                order.evosCancelPending = true
            }
        }

        order.driver = driver
        order.status = OrderStatus.ACCEPTED
        order.acceptedAt = LocalDateTime.now()

        val updatedOrder = orderRepository.save(order)
        redisTemplate.opsForHash<String, Any>().put("orders:active_drivers", driver.id.toString(), updatedOrder.uuid.toString())

        val dto = TaxiOrderDto(updatedOrder)

        messagingTemplate.convertAndSend("/topic/orders/${updatedOrder.id}", dto)
        orderService.broadcastOrderChange(updatedOrder, "UPDATE")

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