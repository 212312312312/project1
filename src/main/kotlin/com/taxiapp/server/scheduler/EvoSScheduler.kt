package com.taxiapp.server.scheduler

import com.taxiapp.server.dto.order.TrackingLocationDto
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

    // 1. Проверка и передача заказов в сеть EvoS (каждые 5 сек)
    @Scheduled(fixedDelay = 5000)
    @Transactional
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

                logger.info(">>> [EvoSScheduler] Замовлення #${order.id} передано в EvoS (UID: $evosUid)")
            }
        }
    }

    // 2. Опрос состояния заказов и GPS-координат в EvoS (каждые 3 сек)
    @Scheduled(fixedDelay = 3000)
    @Transactional
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
            val oldStatus = order.status
            var pushTitle: String? = null
            var pushBody: String? = null


            // --- 1) Данные автомобиля, телефона и рейтинга водителя ---
            if (!evosState.orderCarInfo.isNullOrBlank() && order.evosDriverCarInfo != evosState.orderCarInfo) {
                order.evosDriverCarInfo = evosState.orderCarInfo
                isChanged = true
            }
            if (!evosState.driverPhone.isNullOrBlank() && order.evosDriverPhone != evosState.driverPhone) {
                order.evosDriverPhone = evosState.driverPhone
                isChanged = true
            }
            if (evosState.crewAverageRating != null && order.evosRating != evosState.crewAverageRating) {
                order.evosRating = evosState.crewAverageRating
                isChanged = true
            }

            // --- 2) Обработка статусов заказа ---
            val closeReason = evosState.closeReason ?: -1
            val execStatus = evosState.executionStatus ?: ""
            val driverExecStatus = evosState.driverExecutionStatus ?: 0

            if (execStatus.equals("Executed", ignoreCase = true) || execStatus.equals("Completed", ignoreCase = true) || closeReason == 0) {
                if (order.status != OrderStatus.COMPLETED) {
                    order.status = OrderStatus.COMPLETED
                    order.completedAt = LocalDateTime.now()
                    isChanged = true
                    statusChanged = true
                    pushTitle = "Поїздку завершено"
                    pushBody = "Дякуємо, що скористалися нашими послугами!"
                }
            } else if (execStatus.equals("Canceled", ignoreCase = true) || closeReason > 0) {
                if (order.status != OrderStatus.CANCELLED) {
                    order.status = OrderStatus.CANCELLED
                    order.cancellationReason = evosState.cancelReasonComment ?: "Скасовано партнерською службою (код: $closeReason)"
                    order.completedAt = LocalDateTime.now()
                    isChanged = true
                    statusChanged = true
                    pushTitle = "Замовлення скасовано"
                    pushBody = order.cancellationReason
                }
            } else {
                // Статусы активного выполнения
                val hasCar = !evosState.orderCarInfo.isNullOrBlank()

                when {
                    // Машина на месте ожидает клиента
                    execStatus.equals("CarAtPlace", ignoreCase = true) || driverExecStatus == 2 || driverExecStatus == 4 -> {
                        if (order.status != OrderStatus.DRIVER_ARRIVED) {
                            order.status = OrderStatus.DRIVER_ARRIVED
                            order.arrivedAt = order.arrivedAt ?: LocalDateTime.now()
                            isChanged = true
                            statusChanged = true
                            pushTitle = "Водій на місці"
                            pushBody = "Вас очікує: ${order.evosDriverCarInfo ?: "автомобіль"}"
                        }
                    }
                    // Поездка началась (в пути)
                    execStatus.equals("Executing", ignoreCase = true) || execStatus.equals("Running", ignoreCase = true) || driverExecStatus == 5 -> {
                        if (order.status != OrderStatus.IN_PROGRESS) {
                            order.status = OrderStatus.IN_PROGRESS
                            order.startedAt = order.startedAt ?: LocalDateTime.now()
                            isChanged = true
                            statusChanged = true
                            pushTitle = "В дорозі"
                            pushBody = "Поїздка розпочалася. Гарної дороги!"
                        }
                    }
                    // Водитель назначен / принял заказ
                    execStatus.equals("CarAssigned", ignoreCase = true) || execStatus.equals("CarFound", ignoreCase = true) || hasCar || driverExecStatus == 3 -> {
                        if (order.status != OrderStatus.ACCEPTED && order.status != OrderStatus.DRIVER_ARRIVED && order.status != OrderStatus.IN_PROGRESS) {
                            order.status = OrderStatus.ACCEPTED
                            order.isEvosDriverAssigned = true
                            order.acceptedAt = order.acceptedAt ?: LocalDateTime.now()
                            isChanged = true
                            statusChanged = true
                            pushTitle = "Водія знайдено"
                            pushBody = "До вас прямує: ${order.evosDriverCarInfo ?: "автомобіль"}"
                        }
                    }
                }
            }

            // --- 3) Сохранение и рассылка изменений (UI, Сокеты, Push) ---
            if (isChanged) {
                val saved = orderRepository.save(order)
                val action = if (saved.status == OrderStatus.COMPLETED || saved.status == OrderStatus.CANCELLED) "REMOVE" else "UPDATE"

                // Мгновенно рассылает обновленный заказ с синтетическим DTO водителя клиенту и в веб-панель
                orderService.broadcastOrderChange(saved, action)

                // Отправка FCM пуша клиенту
                if (statusChanged && pushTitle != null && pushBody != null) {
                    notificationService.sendOrderStatusToClient(
                        token = saved.client.fcmToken,
                        orderId = saved.id!!,
                        status = saved.status.name,
                        title = pushTitle,
                        body = pushBody
                    )
                }

                logger.info(">>> [EvoSScheduler] Замовлення #${order.id}: $oldStatus -> ${order.status} (${order.evosDriverCarInfo})")
            }

            // --- 4) GPS трекинг позиции автомобиля ---
            val pos = evosState.drivercarPosition ?: evoSService.getDriverPosition(uid)
            if (pos?.lat != null && pos.lng != null && (pos.status == null || pos.status == "gpsOk")) {
                order.lastEvosLat = pos.lat
                order.lastEvosLng = pos.lng
                order.lastEvosBearing = pos.bearing ?: 0f

                // Чистый DTO координат (lat, lng, bearing)
                val trackingPayload = TrackingLocationDto(
                    lat = pos.lat,
                    lng = pos.lng,
                    bearing = pos.bearing ?: 0f
                )

                // Универсальная Map с дополнительными метаданными для веб-панели и сокетов
                val trackingMap = mapOf(
                    "orderId" to order.id,
                    "lat" to pos.lat,
                    "lng" to pos.lng,
                    "bearing" to (pos.bearing ?: 0f),
                    "speed" to (pos.speed ?: 0),
                    "carInfo" to (order.evosDriverCarInfo ?: "")
                )

                // Рассылаем в каналы трекинга
                messagingTemplate.convertAndSend("/topic/tracking/${order.id}", trackingPayload)
                messagingTemplate.convertAndSend("/topic/orders/${order.id}/tracking", trackingMap)
                messagingTemplate.convertAndSend("/topic/admin/tracking/${order.id}", trackingMap)
            }
        }
    }
}