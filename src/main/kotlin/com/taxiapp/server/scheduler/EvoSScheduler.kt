package com.taxiapp.server.scheduler

import com.taxiapp.server.dto.order.TrackingLocationDto
import com.taxiapp.server.model.enums.OrderStatus
import com.taxiapp.server.repository.TaxiOrderRepository
import com.taxiapp.server.service.EvoSService
import com.taxiapp.server.service.NotificationService
import com.taxiapp.server.service.OrderService
import com.taxiapp.server.service.DriverPayoutService
import com.taxiapp.server.service.SettingsService
import org.slf4j.LoggerFactory
import com.taxiapp.server.service.PromoService
import com.taxiapp.server.service.PromoCodeService
import com.taxiapp.server.service.ChatService
import org.springframework.data.redis.core.RedisTemplate
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
    private val notificationService: NotificationService,
    private val driverPayoutService: DriverPayoutService,
    private val promoService: PromoService, // 👈 ДОБАВЛЕНО
    private val promoCodeService: PromoCodeService, // 👈 ДОБАВЛЕНО
    private val chatService: ChatService, // 👈 ДОБАВЛЕНО
    private val redisTemplate: RedisTemplate<String, Any> // 👈 ДОБАВЛЕНО
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

                    // 1. Очистка активного заказа клиента в Redis
                    redisTemplate.opsForSet().remove("client:active_orders:${order.client.id}", order.id.toString())

                    // 2. Увеличение счетчика поездок клиента
                    order.client.tripsCount += 1

                    // 3. Гашение скидки (акционный план, промокод или награда)
                    if (order.appliedDiscount > 0.0) {
                        if (order.promoPlanId != null) {
                            promoService.markPromoPlanAsUsed(order.client, order.promoPlanId!!)
                        } else if (order.isPromoCodeUsed) {
                            val activePromoUsage = promoCodeService.findActiveUsage(order.client)
                            activePromoUsage?.let { promoCodeService.markAsUsed(it.id) }
                        } else {
                            promoService.markRewardAsUsed(order.client)
                        }

                        // Фиксация доплаты в "Розрахунки з водіями"
                        val clientPays = (order.price - order.appliedDiscount).toInt()
                        val subsidy = order.appliedDiscount.toInt()
                        driverPayoutService.createPayout(
                            driver = null,
                            order = order,
                            amount = order.appliedDiscount,
                            comment = "Доплата партнеру EvoS за знижку (Клієнт: ${clientPays} грн, Доплата: ${subsidy} грн)"
                        )
                        logger.info(">>> [EvoS Payout] Створено розрахунок на суму ${order.appliedDiscount} грн для замовлення #${order.id}")
                    }

                    // 4. Прогресс в маркетинговых заданиях и очистка чата
                    promoService.updateProgressOnRideCompletion(order.client, order)
                    chatService.clearChatForOrder(order.id!!)
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
            // --- 4) GPS трекинг позиции автомобиля ---
            val pos = evosState.drivercarPosition ?: evoSService.getDriverPosition(uid)

            logger.info(">>> [EvoS GPS Raw] Замовлення #${order.id} (UID: $uid): pos=$pos")

            if (pos == null) {
                logger.warn(">>> [EvoS GPS] Об'єкт позиції null для замовлення #${order.id}")
            } else if (pos.lat == null || pos.lng == null || pos.lat == 0.0 || pos.lng == 0.0) {
                logger.warn(">>> [EvoS GPS] Координати відсутні або 0.0: lat=${pos.lat}, lng=${pos.lng}, status=${pos.status}")
            } else {
                logger.info(">>> [EvoS GPS VALID] Замовлення #${order.id}: lat=${pos.lat}, lng=${pos.lng}, bearing=${pos.bearing}, speed=${pos.speed}, status=${pos.status}")

                val lat = pos.lat
                val lng = pos.lng
                val bearing = pos.bearing ?: 0f
                val speed = pos.speed ?: 0

                order.lastEvosLat = lat
                order.lastEvosLng = lng
                order.lastEvosBearing = bearing

                // ➕ Генерация полилинии подачи для партнера EvoS
                if (order.driverToPickupPolyline.isNullOrEmpty() && 
                    order.status == OrderStatus.ACCEPTED && 
                    order.originLat != null && order.originLng != null &&
                    order.originLat != 0.0 && order.originLng != 0.0) {
                    
                    val pickupPoly = orderService.fetchDirectionsPolyline(lat, lng, order.originLat!!, order.originLng!!)
                    if (!pickupPoly.isNullOrEmpty()) {
                        order.driverToPickupPolyline = pickupPoly
                        val saved = orderRepository.save(order)
                        orderService.broadcastOrderChange(saved, "UPDATE")
                        logger.info(">>> [EvoS Route] Маршрут подачі згенеровано через OSRM та надіслано для #${order.id}")
                    }
                }

                val trackingPayload = TrackingLocationDto(
                    lat = lat,
                    lng = lng,
                    bearing = bearing
                )

                val trackingMap = mapOf(
                    "orderId" to (order.uuid?.toString() ?: order.id.toString()),
                    "idLong" to (order.id ?: 0L),
                    "lat" to lat,
                    "lng" to lng,
                    "bearing" to bearing,
                    "speed" to speed,
                    "status" to (pos.status ?: "gpsOk"),
                    "carInfo" to (order.evosDriverCarInfo ?: "")
                )

                val orderUuidStr = order.uuid.toString()
                val orderIdLongStr = order.id.toString()

                // Рассылка клиенту
                messagingTemplate.convertAndSend("/topic/order/$orderUuidStr/tracking", trackingPayload)
                messagingTemplate.convertAndSend("/topic/order/$orderIdLongStr/tracking", trackingPayload)
                messagingTemplate.convertAndSend("/topic/orders/$orderUuidStr/tracking", trackingMap)

                // Рассылка в диспетчерскую
                messagingTemplate.convertAndSend("/topic/admin/tracking/$orderUuidStr", trackingMap)
                messagingTemplate.convertAndSend("/topic/admin/tracking/$orderIdLongStr", trackingMap)
                messagingTemplate.convertAndSend("/topic/admin/drivers-location", listOf(trackingMap))
                
                logger.info(">>> [EvoS GPS STOMP SENT] Координати успішно відправлені в сокети для #${order.id}")
            }
        }
    }


    // 3. Повторная отмена зависших заказов в сети EvoS (каждые 5 сек)
    @Scheduled(fixedDelay = 5000)
    @Transactional
    fun retryCancelPendingEvosOrders() {
        if (!settingsService.isEvosEnabled()) return

        val pendingOrders = orderRepository.findAllByEvosCancelPendingTrue()
        if (pendingOrders.isEmpty()) return

        for (order in pendingOrders) {
            val uid = order.evosOrderUid ?: continue

            // 1. Проверяем текущее состояние в EvoS
            val evosState = evoSService.getOrderState(uid)
            val isAlreadyClosed = evosState != null && (
                evosState.executionStatus.equals("Canceled", ignoreCase = true) ||
                (evosState.closeReason != null && evosState.closeReason > 0) ||
                evosState.orderIsArchive == true
            )

            if (isAlreadyClosed) {
                logger.info(">>> [EvoS Retry] Заказ #${order.id} (UID: $uid) уже закрыт в EvoS. Снимаем флаг ожидания.")
                order.evosCancelPending = false
                order.isSentToEvos = false
                orderRepository.save(order)
                continue
            }

            // 2. Если всё еще открыт — повторяем запрос на отмену
            logger.info(">>> [EvoS Retry] Повторная попытка отмены заказа #${order.id} (UID: $uid)...")
            val isSuccess = evoSService.cancelOrderInEvoS(uid)
            if (isSuccess) {
                logger.info(">>> [EvoS Retry] Заказ #${order.id} (UID: $uid) успешно отменен в EvoS.")
                order.evosCancelPending = false
                order.isSentToEvos = false
                orderRepository.save(order)
            } else {
                logger.warn(">>> [EvoS Retry] Заказ #${order.id} (UID: $uid) не удалось отменить. Повтор через 5 сек.")
            }
        }
    }
}