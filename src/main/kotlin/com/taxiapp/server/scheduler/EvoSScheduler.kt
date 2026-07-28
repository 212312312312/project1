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

        // Ищем заказы в поиске, которые еще не перекинуты в EvoS
        val candidateOrders = orderRepository.findAllByStatus(OrderStatus.REQUESTED)
            .filter { !it.isSentToEvos && it.createdAt.isBefore(thresholdTime) && it.scheduledAt == null }

        for (order in candidateOrders) {
            val evosUid = evoSService.sendOrderToEvoS(order)
            if (evosUid != null) {
                order.evosOrderUid = evosUid
                order.isSentToEvos = true
                orderRepository.save(order)
                logger.info(">>> [EvoSScheduler] Заказ #${order.id} помечен как отправленный в EvoS (UID: $evosUid)")
            }
        }
    }

    // 2. Поллинг состояния заказов в EvoS и трекинг GPS (Каждые 3 секунды)
    @Scheduled(fixedDelay = 3000)
    @Transactional
    fun pollEvosOrdersState() {
        if (!settingsService.isEvosEnabled()) return

        val activeEvosOrders = orderRepository.findAll()
            .filter { it.isSentToEvos && !it.evosOrderUid.isNullOrEmpty() && it.status != OrderStatus.COMPLETED && it.status != OrderStatus.CANCELLED }

        for (order in activeEvosOrders) {
            val uid = order.evosOrderUid ?: continue
            val evosState = evoSService.getOrderState(uid) ?: continue

            // --- А) Взятие заказа водителем из EvoS ---
            val hasCarInfo = !evosState.orderCarInfo.isNullOrBlank()
            val isCarFound = evosState.executionStatus in listOf("CarFound", "Running") || hasCarInfo

            if (isCarFound && !order.isEvosDriverAssigned) {
                logger.info(">>> [EvoS] Водитель из EvoS взял заказ #${order.id}! Машина: ${evosState.orderCarInfo}")
                order.isEvosDriverAssigned = true
                order.evosDriverCarInfo = evosState.orderCarInfo
                order.evosDriverPhone = evosState.driverPhone
                order.status = OrderStatus.ACCEPTED
                
                val saved = orderRepository.save(order)

                // Уведомляем клиента и диспетчера через сокеты
                orderService.broadcastOrderChange(saved, "UPDATE")

                // Пушим уведомление клиенту
                notificationService.sendOrderStatusToClient(
                    token = saved.client.fcmToken,
                    orderId = saved.id!!,
                    status = saved.status.name,
                    title = "Водій знайдений (Партнер)",
                    body = "Замовлення прийнято водієм: ${evosState.orderCarInfo}"
                )
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
                if (closeReason == 0 || evosState.executionStatus == "Executed") {
                    order.status = OrderStatus.COMPLETED
                    order.completedAt = LocalDateTime.now()
                } else if (closeReason in listOf(1, 2, 3, 4, 6, 7)) {
                    order.status = OrderStatus.CANCELLED
                    order.cancellationReason = "Отменено в партнерской сети EvoS (код: $closeReason)"
                    order.completedAt = LocalDateTime.now()
                }
                val saved = orderRepository.save(order)
                orderService.broadcastOrderChange(saved, "REMOVE")
            }
        }
    }
}