package com.taxiapp.server.scheduler

import com.taxiapp.server.dto.order.TrackingLocationDto
import com.taxiapp.server.model.enums.OrderStatus
import com.taxiapp.server.model.order.TaxiOrderTrack
import com.taxiapp.server.repository.TaxiOrderRepository
import com.taxiapp.server.repository.TaxiOrderTrackRepository
import com.taxiapp.server.service.ChatService
import com.taxiapp.server.service.DriverPayoutService
import com.taxiapp.server.service.EvoSService
import com.taxiapp.server.service.NotificationService
import com.taxiapp.server.service.OrderService
import com.taxiapp.server.service.PromoCodeService
import com.taxiapp.server.service.PromoService
import com.taxiapp.server.service.SettingsService
import com.taxiapp.server.utils.GeometryUtils
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Component
class EvoSScheduler(
    private val orderRepository: TaxiOrderRepository,
    private val evoSService: EvoSService,
    private val settingsService: SettingsService,
    private val orderService: OrderService,
    private val messagingTemplate: SimpMessagingTemplate,
    private val notificationService: NotificationService,
    private val driverPayoutService: DriverPayoutService,
    private val promoService: PromoService,
    private val promoCodeService: PromoCodeService,
    private val chatService: ChatService,
    private val redisTemplate: RedisTemplate<String, Any>,
    private val taxiOrderTrackRepository: TaxiOrderTrackRepository
) {
    private val logger = LoggerFactory.getLogger(EvoSScheduler::class.java)

    // Локальный кэш последней записанной в БД точки трека для заказа EvoS (orderId -> Triple(lat, lng, timestampMs))
    private val lastSavedEvosTrackMap = ConcurrentHashMap<Long, Triple<Double, Double, Long>>()

    // 1. Проверка и передача заказов в сеть EvoS (каждые 5 сек)
    @Scheduled(fixedDelay = 5000)
    @Transactional
    fun processOrdersForEvos() {
        if (!settingsService.isEvosEnabled()) return

        val delaySeconds = settingsService.getEvosDelaySeconds()
        val thresholdTime = LocalDateTime.now().minusSeconds(delaySeconds)

        val candidateOrders = orderRepository.findAllByStatusInAndIsSentToEvosFalseAndCreatedAtBefore(
            listOf(OrderStatus.REQUESTED, OrderStatus.SCHEDULED),
            thresholdTime
        )

        for (order in candidateOrders) {
            val evosUid = evoSService.sendOrderToEvoS(order)
            if (evosUid != null) {
                order.evosOrderUid = evosUid
                order.isSentToEvos = true

                val saved = orderRepository.save(order)
                orderService.broadcastOrderChange(saved, "UPDATE")

                logger.info(">>> [EvoSScheduler] Замовлення #${order.id} (Статус: ${order.status}) передано в EvoS (UID: $evosUid)")
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

                    redisTemplate.opsForSet().remove("client:active_orders:${order.client.id}", order.id.toString())
                    lastSavedEvosTrackMap.remove(order.id)

                    order.client.tripsCount += 1

                    if (order.appliedDiscount > 0.0) {
                        if (order.promoPlanId != null) {
                            promoService.markPromoPlanAsUsed(order.client, order.promoPlanId!!)
                        } else if (order.isPromoCodeUsed) {
                            val activePromoUsage = promoCodeService.findActiveUsage(order.client)
                            activePromoUsage?.let { promoCodeService.markAsUsed(it.id) }
                        } else {
                            promoService.markRewardAsUsed(order.client)
                        }

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
                    redisTemplate.opsForSet().remove("client:active_orders:${order.client.id}", order.id.toString())
                    lastSavedEvosTrackMap.remove(order.id)
                }
            } else {
                val hasCar = !evosState.orderCarInfo.isNullOrBlank()

                when {
                    execStatus.equals("CarAtPlace", ignoreCase = true) || driverExecStatus == 2 || driverExecStatus == 4 -> {
                        if (order.status != OrderStatus.DRIVER_ARRIVED) {
                            order.status = OrderStatus.DRIVER_ARRIVED
                            order.isEvosDriverAssigned = true
                            order.arrivedAt = order.arrivedAt ?: LocalDateTime.now()
                            isChanged = true
                            statusChanged = true
                            pushTitle = "Водій на місці"
                            pushBody = "Вас очікує: ${order.evosDriverCarInfo ?: "автомобіль"}"
                        }
                    }
                    execStatus.equals("Executing", ignoreCase = true) || execStatus.equals("Running", ignoreCase = true) || driverExecStatus == 5 -> {
                        if (order.status != OrderStatus.IN_PROGRESS) {
                            order.status = OrderStatus.IN_PROGRESS
                            order.isEvosDriverAssigned = true
                            order.startedAt = order.startedAt ?: LocalDateTime.now()
                            isChanged = true
                            statusChanged = true
                            pushTitle = "В дорозі"
                            pushBody = "Поїздка розпочалася. Гарної дороги!"
                        }
                    }
                    driverExecStatus == 10 -> {
                        if (!order.isEvosDriverAssigned) {
                            order.isEvosDriverAssigned = true
                            isChanged = true
                            statusChanged = true
                            pushTitle = "Попереднє замовлення підтверджено"
                            pushBody = "Водій підтвердив завчасну подачу: ${order.evosDriverCarInfo ?: "автомобіль"}"
                        }
                    }
                    execStatus.equals("CarAssigned", ignoreCase = true) || execStatus.equals("CarFound", ignoreCase = true) || hasCar || driverExecStatus == 3 -> {
                        if (order.status == OrderStatus.SCHEDULED) {
                            val isNearExecution = order.scheduledAt?.isBefore(LocalDateTime.now().plusMinutes(30)) ?: true
                            if (!order.isEvosDriverAssigned) {
                                order.isEvosDriverAssigned = true
                                isChanged = true
                                statusChanged = true
                                pushTitle = "Водія знайдено"
                                pushBody = "На попереднє замовлення призначено: ${order.evosDriverCarInfo ?: "автомобіль"}"
                            }
                            if (isNearExecution && order.status != OrderStatus.ACCEPTED) {
                                order.status = OrderStatus.ACCEPTED
                                order.acceptedAt = order.acceptedAt ?: LocalDateTime.now()
                                isChanged = true
                                statusChanged = true
                            }
                        } else if (order.status != OrderStatus.ACCEPTED && order.status != OrderStatus.DRIVER_ARRIVED && order.status != OrderStatus.IN_PROGRESS) {
                            order.status = OrderStatus.ACCEPTED
                            order.isEvosDriverAssigned = true
                            order.acceptedAt = order.acceptedAt ?: LocalDateTime.now()
                            isChanged = true
                            statusChanged = true
                            pushTitle = "Водія знайдено"
                            pushBody = "До вас прямує: ${order.evosDriverCarInfo ?: "автомобіль"}"
                        }
                    }
                    execStatus.equals("WaitingCarSearch", ignoreCase = true) -> {
                        logger.debug(">>> [EvoS] Предзаказ #${order.id} в статусе WaitingCarSearch")
                    }
                }
            }

            // --- 3) Сохранение и рассылка изменений (UI, Сокеты, Push) ---
            if (isChanged) {
                val saved = orderRepository.save(order)
                val action = if (saved.status == OrderStatus.COMPLETED || saved.status == OrderStatus.CANCELLED) "REMOVE" else "UPDATE"

                orderService.broadcastOrderChange(saved, action)

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
            val hasAssignedCar = !evosState.orderCarInfo.isNullOrBlank() || order.isEvosDriverAssigned

            if (hasAssignedCar && order.status != OrderStatus.COMPLETED && order.status != OrderStatus.CANCELLED) {
                val pos = evosState.drivercarPosition ?: evoSService.getDriverPosition(uid)

                if (pos != null && pos.lat != null && pos.lng != null && pos.lat != 0.0 && pos.lng != 0.0) {
                    val lat = pos.lat
                    val lng = pos.lng
                    val bearing = pos.bearing ?: 0f
                    val speed = pos.speed ?: 0
                    val nowMs = System.currentTimeMillis()
                    val orderIdLong = order.id!!

                    order.lastEvosLat = lat
                    order.lastEvosLng = lng
                    order.lastEvosBearing = bearing

                    // Троттлинг записи в PostgreSQL (пишем в БД только при смещении >= 10 м или раз в 15 сек)
                    val prevTrack = lastSavedEvosTrackMap[orderIdLong]
                    val shouldSaveToDb = if (prevTrack == null) {
                        true
                    } else {
                        val distMoved = GeometryUtils.calculateDistanceMeters(prevTrack.first, prevTrack.second, lat, lng)
                        val timeElapsed = nowMs - prevTrack.third
                        distMoved >= 10.0 || timeElapsed >= 15_000L
                    }

                    try {
                        if (shouldSaveToDb) {
                            val dbTrack = TaxiOrderTrack(
                                orderId = orderIdLong,
                                latitude = lat,
                                longitude = lng
                            )
                            taxiOrderTrackRepository.save(dbTrack)
                            lastSavedEvosTrackMap[orderIdLong] = Triple(lat, lng, nowMs)
                        }

                        val trackKey = "orders:track-history:${order.uuid}"
                        val timestamp = Instant.now().toString()
                        redisTemplate.opsForList().rightPush(trackKey, "$lat,$lng,$timestamp")
                        redisTemplate.expire(trackKey, 7, TimeUnit.DAYS)
                    } catch (e: Exception) {
                        logger.error(">>> [EvoS Track] Помилка запису точки в БД: ${e.message}")
                    }

                    // Генерация полилинии подачи для партнера EvoS
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

                    messagingTemplate.convertAndSend("/topic/order/$orderUuidStr/tracking", trackingPayload)
                    messagingTemplate.convertAndSend("/topic/order/$orderIdLongStr/tracking", trackingPayload)
                    messagingTemplate.convertAndSend("/topic/orders/$orderUuidStr/tracking", trackingMap)

                    messagingTemplate.convertAndSend("/topic/admin/tracking/$orderUuidStr", trackingMap)
                    messagingTemplate.convertAndSend("/topic/admin/tracking/$orderIdLongStr", trackingMap)
                    messagingTemplate.convertAndSend("/topic/admin/drivers-location", listOf(trackingMap))
                }
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