package com.taxiapp.server.scheduler

import com.taxiapp.server.dto.order.OrderSocketMessage
import com.taxiapp.server.dto.order.TaxiOrderDto
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
import org.springframework.transaction.annotation.Transactional
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
    @Transactional
    fun processOrdersForEvos() {
        if (!settingsService.isEvosEnabled()) return

        val delaySeconds = settingsService.getEvosDelaySeconds()
        val thresholdTime = LocalDateTime.now().minusSeconds(delaySeconds)

        // Раньше стояло filtering (it.scheduledAt == null), из-за чего заказы на время пропускались.
        // Теперь отсылаем ВСЕ неперекинутые заказы.
        val candidateOrders = orderRepository.findAllByStatus(OrderStatus.REQUESTED)
            .filter { !it.isSentToEvos && it.createdAt.isBefore(thresholdTime) }

for (order in candidateOrders) {
            val evosUid = evoSService.sendOrderToEvoS(order)
            if (evosUid != null) {
                order.evosOrderUid = evosUid
                order.isSentToEvos = true
                val saved = orderRepository.save(order)
                
                // 🟢 Мгновенно уведомляем фронтенд, что заказ улетел в EvoS
                orderService.broadcastOrderChange(saved, "UPDATE")

                logger.info(">>> [EvoSScheduler] Заказ #${order.id} помечен как отправленный в EvoS (UID: $evosUid)")
            }
        }
    }

    @Scheduled(fixedDelay = 3000)
@Transactional
fun pollEvosOrdersState() {
    if (!settingsService.isEvosEnabled()) return

    val activeEvosOrders = orderRepository.findAll()
        .filter { it.isSentToEvos && !it.evosOrderUid.isNullOrEmpty() && it.status != OrderStatus.COMPLETED && it.status != OrderStatus.CANCELLED }

    for (order in activeEvosOrders) {
        val uid = order.evosOrderUid ?: continue
        val evosState = evoSService.getOrderState(uid) ?: continue

        // --- 0) Прием километража и пересчитанной стоимости от бэкенда EvoS ---
        evosState.orderCost?.toDoubleOrNull()?.let { partnerCalculatedPrice ->
            if (partnerCalculatedPrice > 0.0 && order.price != partnerCalculatedPrice) {
                logger.info(">>> [EvoS] Обновлена стоимость заказа #${order.id} по километражу EvoS: ${order.price} грн -> $partnerCalculatedPrice грн")
                order.price = partnerCalculatedPrice
                val savedPriceOrder = orderRepository.save(order)
                orderService.broadcastOrderChange(savedPriceOrder, "UPDATE")
            }
        }

        // --- А) Взятие и изменение статусов движения заказа из EvoS ---
        val hasCarInfo = !evosState.orderCarInfo.isNullOrBlank()
        val isCarFound = evosState.executionStatus in listOf("CarFound", "Running") || hasCarInfo
        val driverExecStatus = evosState.driverExecutionStatus ?: 0

        if (isCarFound) {
            var updatedStatus = order.status

            // Назначен водитель
            if (!order.isEvosDriverAssigned) {
                order.isEvosDriverAssigned = true
                order.evosDriverCarInfo = evosState.orderCarInfo
                order.evosDriverPhone = evosState.driverPhone
                updatedStatus = OrderStatus.ACCEPTED
            }

            // Машина подана на адрес (driverExecutionStatus == 2 "По адресу")
            if (driverExecStatus == 2 && updatedStatus != OrderStatus.DRIVER_ARRIVED) {
                updatedStatus = OrderStatus.DRIVER_ARRIVED
            }

            // Поездка началась (driverExecutionStatus == 5 "С пассажиром" или executionStatus == "Running")
            if ((driverExecStatus == 5 || evosState.executionStatus == "Running") && updatedStatus != OrderStatus.IN_PROGRESS) {
                updatedStatus = OrderStatus.IN_PROGRESS
            }

            if (updatedStatus != order.status) {
                order.status = updatedStatus
                val saved = orderRepository.save(order)
                orderService.broadcastOrderChange(saved, "UPDATE")

                notificationService.sendOrderStatusToClient(
                    token = saved.client.fcmToken,
                    orderId = saved.id!!,
                    status = saved.status.name,
                    title = "Статус замовлення змінено",
                    body = "Поточний статус: ${saved.status.name} (${evosState.orderCarInfo ?: ""})"
                )
            }
        }

        // --- Б) Синхронизация GPS координат водителя EvoS ---
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
            // Транслируем GPS клиенту и в диспетчерскую
            messagingTemplate.convertAndSend("/topic/orders/${order.id}/tracking", trackingMap)
            messagingTemplate.convertAndSend("/topic/admin/tracking/${order.id}", trackingMap)
        }

        // --- В) Завершение или отмена заказа со стороны EvoS ---
        if (evosState.orderIsArchive == true || evosState.executionStatus == "Canceled" || (evosState.closeReason ?: -1) >= 0) {
            val closeReason = evosState.closeReason ?: -1
            val execStatus = evosState.executionStatus

            // 1. Успешно выполненный заказ водителем партнеров
            if (execStatus == "Executed") {
                logger.info(">>> [EvoS] Заказ #${order.id} успешно выполнен водителем EvoS.")
                order.status = OrderStatus.COMPLETED
                order.completedAt = LocalDateTime.now()
                val saved = orderRepository.save(order)
                orderService.broadcastOrderChange(saved, "REMOVE")
            } 
            // 2. Отмена заказа у партнеров
            else if (execStatus == "Canceled" || closeReason >= 0 || evosState.orderIsArchive == true) {
                logger.info(">>> [EvoS] Заказ #${order.id} отменен в сеть EvoS (close_reason: $closeReason, status: $execStatus).")
                order.status = OrderStatus.CANCELLED
                order.cancellationReason = "Скасовано в партнерській мережі EvoS (код: $closeReason)"
                order.completedAt = LocalDateTime.now()
                val saved = orderRepository.save(order)
                orderService.broadcastOrderChange(saved, "REMOVE")
            }
        }
    }
}
}