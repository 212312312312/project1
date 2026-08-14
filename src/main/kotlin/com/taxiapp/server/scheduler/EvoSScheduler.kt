package com.taxiapp.server.scheduler

import com.taxiapp.server.model.enums.OrderStatus
import com.taxiapp.server.repository.TaxiOrderRepository
import com.taxiapp.server.service.EvoSService
import com.taxiapp.server.service.NotificationService
import com.taxiapp.server.service.OrderService
import com.taxiapp.server.service.SettingsService
import org.slf4j.LoggerFactory
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDateTime

@Component
class EvoSScheduler(
    private val orderRepository: TaxiOrderRepository,
    private val evoSService: EvoSService,
    private val settingsService: SettingsService,
    private val orderService: OrderService,
    private val messagingTemplate: SimpMessagingTemplate,
    private val notificationService: NotificationService
) {
    private val logger = LoggerFactory.getLogger(EvoSScheduler::class.java)

    // 1. Проверка заказов для отправки в EvoS (Каждые 5 секунд)
    @Scheduled(fixedDelay = 5000)
    fun processOrdersForEvos() {
        if (!settingsService.isEvosEnabled()) return

        val delaySeconds = settingsService.getEvosDelaySeconds()
        val thresholdTime = LocalDateTime.now().minusSeconds(delaySeconds)

        val candidateOrders = orderRepository.findAllByStatusAndIsSentToEvosFalseAndCreatedAtBefore(
            OrderStatus.REQUESTED, 
            thresholdTime
        )

        for (order in candidateOrders) {
            val evosUid = evoSService.sendOrderToEvoS(order)
            if (evosUid != null) {
                order.evosOrderUid = evosUid
                order.isSentToEvos = true
                
                val saved = orderRepository.save(order)
                orderService.broadcastOrderChange(saved, "UPDATE")

                logger.info(">>> [EvoSScheduler] Заказ #${order.id} отправлен в EvoS (UID: $evosUid)")
            }
        }
    }

    // 2. Опрос состояния заказов в EvoS (Каждые 3 секунды)
    @Scheduled(fixedDelay = 3000)
    fun pollEvosOrdersState() {
        if (!settingsService.isEvosEnabled()) return

        val activeEvosOrders = orderRepository.findAllByIsSentToEvosTrueAndStatusNotIn(
            listOf(OrderStatus.COMPLETED, OrderStatus.CANCELLED)
        )

        for (order in activeEvosOrders) {
            val uid = order.evosOrderUid
            if (uid.isNullOrEmpty()) continue

            val evosState = evoSService.getOrderState(uid) ?: continue

            var isChanged = false
            var statusChanged = false

            // --- 0) Синхронизация цены ---
            evosState.orderCost?.toDoubleOrNull()?.let { partnerCalculatedPrice ->
                if (partnerCalculatedPrice > 0.0 && order.price != partnerCalculatedPrice) {
                    logger.info(">>> [EvoS] Изменена цена заказа #${order.id}: ${order.price} грн -> $partnerCalculatedPrice грн")
                    order.price = partnerCalculatedPrice
                    isChanged = true
                }
            }

            // --- А) Проверка завершения / отмены ---
            val closeReason = evosState.closeReason ?: -1
            val execStatus = evosState.executionStatus

            if (execStatus == "Executed") {
                logger.info(">>> [EvoS] Заказ #${order.id} успешно выполнен водителем EvoS.")
                order.status = OrderStatus.COMPLETED
                order.completedAt = LocalDateTime.now()
                isChanged = true
                statusChanged = true
            } 
            else if (execStatus == "Canceled" || closeReason > 0) {
                logger.info(">>> [EvoS] Заказ #${order.id} отменен в сеть EvoS (closeReason: $closeReason, status: $execStatus).")
                order.status = OrderStatus.CANCELLED
                order.cancellationReason = "Скасовано в партнерській мережі EvoS (код: $closeReason)"
                order.completedAt = LocalDateTime.now()
                isChanged = true
                statusChanged = true
            } 
            else {
                // --- Б) Изменение статусов движения (если заказ еще активен) ---
                val hasCarInfo = !evosState.orderCarInfo.isNullOrBlank()
                val isCarFound = execStatus in listOf("CarFound", "Running") || hasCarInfo
                val driverExecStatus = evosState.driverExecutionStatus ?: 0

                if (isCarFound) {
                    val oldStatus = order.status
                    var newStatus = order.status

                    if (!order.isEvosDriverAssigned) {
                        order.isEvosDriverAssigned = true
                        order.evosDriverCarInfo = evosState.orderCarInfo
                        order.evosDriverPhone = evosState.driverPhone
                        newStatus = OrderStatus.ACCEPTED
                        isChanged = true
                    }

                    if (driverExecStatus == 2 && newStatus != OrderStatus.DRIVER_ARRIVED) {
                        newStatus = OrderStatus.DRIVER_ARRIVED
                    }

                    if ((driverExecStatus == 5 || execStatus == "Running") && newStatus != OrderStatus.IN_PROGRESS) {
                        newStatus = OrderStatus.IN_PROGRESS
                    }

                    if (newStatus != oldStatus) {
                        order.status = newStatus
                        isChanged = true
                        statusChanged = true
                    }
                }
            }

            // --- В) Сохранение и уведомление (один раз за итерацию) ---
            if (isChanged) {
                val saved = orderRepository.save(order)
                val action = if (saved.status == OrderStatus.COMPLETED || saved.status == OrderStatus.CANCELLED) "REMOVE" else "UPDATE"
                
                orderService.broadcastOrderChange(saved, action)

                if (statusChanged && saved.status != OrderStatus.COMPLETED && saved.status != OrderStatus.CANCELLED) {
                    notificationService.sendOrderStatusToClient(
                        token = saved.client.fcmToken,
                        orderId = saved.id!!,
                        status = saved.status.name,
                        title = "Статус замовлення змінено",
                        body = "Поточний статус: ${saved.status.name} (${evosState.orderCarInfo ?: ""})"
                    )
                }
            }

            // --- Г) Синхронизация GPS координат водителя ---
            val pos = evosState.drivercarPosition ?: evoSService.getDriverPosition(uid)
            if (pos?.lat != null && pos.lng != null && pos.status == "gpsOk") {
                val trackingMap = mapOf(
                    "orderId" to order.id,
                    "lat" to pos.lat,
                    "lng" to pos.lng,
                    "bearing" to (pos.bearing ?: 0f),
                    "speed" to (pos.speed ?: 0),
                    "carInfo" to (order.evosDriverCarInfo ?: "")
                )
                messagingTemplate.convertAndSend("/topic/orders/${order.id}/tracking", trackingMap)
                messagingTemplate.convertAndSend("/topic/admin/tracking/${order.id}", trackingMap)
            }
        }
    }
}