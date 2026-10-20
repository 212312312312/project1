package com.taxiapp.server.service

import com.taxiapp.server.dto.driver.HeatmapZoneDto
import com.taxiapp.server.dto.order.CalculatedTariffDto
import com.taxiapp.server.dto.order.CreateOrderRequestDto
import com.taxiapp.server.dto.order.OrderSocketMessage
import com.taxiapp.server.dto.order.TaxiOrderDto
import com.taxiapp.server.dto.tariff.CarTariffDto
import com.taxiapp.server.model.enums.OrderStatus
import com.taxiapp.server.model.filter.DriverFilter
import com.taxiapp.server.model.order.OrderStop
import com.taxiapp.server.model.order.TaxiOrder
import com.taxiapp.server.model.user.Client
import com.taxiapp.server.model.user.Driver
import com.taxiapp.server.model.user.User
import com.taxiapp.server.repository.*
import com.taxiapp.server.utils.GeometryUtils
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.max

@Service
class OrderService(
    private val chatService: ChatService,
    private val notificationService: NotificationService,
    private val orderRepository: TaxiOrderRepository,
    private val tariffRepository: CarTariffRepository,
    private val clientRepository: ClientRepository,
    private val promoService: PromoService,
    private val driverRepository: DriverRepository,
    private val promoCodeService: PromoCodeService,
    private val tariffAdminService: TariffAdminService,
    private val taxiServiceRepository: TaxiServiceRepository,
    private val filterRepository: DriverFilterRepository,
    private val sectorRepository: SectorRepository,
    private val messagingTemplate: SimpMessagingTemplate,
    private val driverActivityService: DriverActivityService,
    private val sectorService: SectorService,
    private val surgePricingService: SurgePricingService,
    private val cancellationReasonRepository: CancellationReasonRepository,
    private val walletTransactionRepository: WalletTransactionRepository,
    private val appSettingRepository: AppSettingRepository,
    private val evoSService: EvoSService,
    private val liqPayService: LiqPayService,
    private val driverPayoutService: DriverPayoutService,
    private val activityHistoryRepository: DriverActivityHistoryRepository,
    private val redisTemplate: org.springframework.data.redis.core.RedisTemplate<String, Any>
) {

    private val logger = LoggerFactory.getLogger(OrderService::class.java)

    private fun calculateExactTripPrice(
        tariffId: Long,
        polyline: String?,
        totalDistanceMeters: Int,
        serviceIds: List<Long>?,
        addedValue: Double,
        waypointsCount: Int = 0,
        originLat: Double? = null,
        originLng: Double? = null,
        isDebug: Boolean = false
    ): Double {
        val tariff = tariffRepository.findById(tariffId)
            .orElseThrow { RuntimeException("Tariff not found") }

        val safeAddedValue = max(0.0, addedValue)

        logger.info("[PRICE_CALC] === НАЧАЛО РАСЧЕТА ЦЕНЫ ЗАКАЗА ===")
        logger.info("[PRICE_CALC] ШАГ 1: Тариф ID=${tariff.id} (${tariff.name}) | BasePrice=${tariff.basePrice} | WaypointPrice=${tariff.extraWaypointPrice} | AddedValue=$safeAddedValue")

        // 1. Считаем стоимость услуг
        var servicesCost = 0.0
        if (!serviceIds.isNullOrEmpty()) {
            val services = taxiServiceRepository.findAllById(serviceIds)
            servicesCost = services.sumOf { it.price }
            logger.info("[PRICE_CALC] ШАГ 2: Выбраны доп. услуги (${services.size} шт.) на сумму: $servicesCost")
        } else {
            logger.info("[PRICE_CALC] ШАГ 2: Доп. услуги не выбраны (Сумма: 0.0)")
        }

        // Считаем стоимость промежуточных точек
        val waypointsCost = waypointsCount * tariff.extraWaypointPrice
        logger.info("[PRICE_CALC] ШАГ 2.1: Промежуточные точки ($waypointsCount шт.) на сумму: $waypointsCost")

        // 2. Если ни полилайна, ни дистанции нет, считаем базу + услуги + точки + надбавка
        if (polyline.isNullOrEmpty() && totalDistanceMeters <= 0) {
            val totalNoDist = max(tariff.basePrice, ceil(tariff.basePrice + servicesCost + safeAddedValue + waypointsCost))
            logger.info("[PRICE_CALC] ШАГ 3: Полилайн и дистанция пусты. Возвращаем (База+Услуги+Точки+Надбавка) = $totalNoDist")
            logger.info("[PRICE_CALC] === КОНЕЦ РАСЧЕТА ===")
            return totalNoDist
        }

        // 3. Расчет по километражу (город/межгород)
        logger.info("[PRICE_CALC] ШАГ 3: Запрос разбивки дистанции у GeometryUtils. Передано: $totalDistanceMeters м.")
        val citySectors = sectorService.getCitySectors()
        val (metersCity, metersOutCity) = if (!polyline.isNullOrEmpty()) {
            GeometryUtils.calculateRouteSplit(polyline, citySectors)
        } else {
            Pair(0.0, 0.0)
        }

        val finalMetersCity = if (metersCity == 0.0 && metersOutCity == 0.0) max(0, totalDistanceMeters).toDouble() else metersCity
        val finalMetersOutCity = if (metersCity == 0.0 && metersOutCity == 0.0) 0.0 else metersOutCity

        val totalKmCity = finalMetersCity / 1000.0
        val totalKmOutCity = finalMetersOutCity / 1000.0

        // 4. Бесплатные километры (динамическое получение из глобальных настроек агрегатора Unit)
        val minDistanceSetting = appSettingRepository.findById("min_order_distance").orElse(null)
        val INCLUDED_KM = minDistanceSetting?.value?.toDoubleOrNull() ?: 3.0
        var remainingIncluded = INCLUDED_KM
        var billableKmCity = 0.0
        if (totalKmCity > remainingIncluded) {
            billableKmCity = totalKmCity - remainingIncluded
            remainingIncluded = 0.0
        } else {
            remainingIncluded -= totalKmCity
            billableKmCity = 0.0
        }

        var billableKmOutCity = 0.0
        if (totalKmOutCity > 0) {
            if (remainingIncluded > 0) {
                billableKmOutCity = if (totalKmOutCity > remainingIncluded) totalKmOutCity - remainingIncluded else 0.0
            } else {
                billableKmOutCity = totalKmOutCity
            }
        }

        // 5. Итоговая сборка цены
        val routePrice = (billableKmCity * tariff.pricePerKm) + (billableKmOutCity * tariff.pricePerKmOutCity)

        // ВРАХУВАННЯ ДИНАМІЧНИХ КОЕФІЦІЄНТІВ (Час + Погода)
        val (pickupLat, pickupLng) = if (originLat != null && originLng != null) {
            Pair(originLat, originLng)
        } else {
            GeometryUtils.getFirstPointFromPolyline(polyline) ?: Pair(null, null)
        }

        val surgeMultiplier = surgePricingService.calculateCurrentSurgeMultiplier(pickupLat, pickupLng)
        val surgedTripBase = (tariff.basePrice + routePrice) * surgeMultiplier

        var finalPrice = surgedTripBase + servicesCost + safeAddedValue + waypointsCost

        logger.info("[PRICE_CALC] Збірка: (База + КМ)*Surge($surgeMultiplier) [Lat=$pickupLat, Lng=$pickupLng] + Послуги($servicesCost) + Точки($waypointsCost) + Надбавка($safeAddedValue) = $finalPrice")

        finalPrice = ceil(finalPrice)
        val result = max(finalPrice, tariff.basePrice)

        return result
    }

    fun calculatePricesForRoute(
        polyline: String,
        totalMeters: Int,
        waypointsCount: Int = 0,
        client: Client? = null,
        originLat: Double? = null,
        originLng: Double? = null
    ): List<CarTariffDto> {
        val allCachedTariffs = tariffAdminService.getAllTariffs()
        val activeTariffs = allCachedTariffs.filter { it.isActive }

        // HIGH-LOAD ОПТИМИЗАЦИЯ: Запрашиваем скидки и промокоды клиента РОВНО 1 РАЗ перед циклом по тарифам (вместо 5-6 раз подряд!)
        val hasActiveDiscount = if (client != null) orderRepository.hasActiveOrderWithDiscount(client.id!!) else true
        val activePromoUsage = if (client != null && !hasActiveDiscount) promoCodeService.findActiveUsage(client) else null
        val isPromoValid = activePromoUsage != null && (activePromoUsage.expiresAt == null || !LocalDateTime.now().isAfter(activePromoUsage.expiresAt))
        val activeReward = if (client != null && !hasActiveDiscount && !isPromoValid) promoService.findActiveReward(client) else null
        val activeRegPlan = if (client != null && !hasActiveDiscount) promoService.findActiveRegistrationDiscountPlan(client) else null
        val activeFreeMinPlan = if (client != null && !hasActiveDiscount) promoService.findActiveFreeMinPlan(client) else null

        if (client != null && hasActiveDiscount) {
            logger.debug(">>> [Discount] Розрахунок цін для клієнта #${client.id} без знижки: вже є активне замовлення зі знижкою.")
        }

        return activeTariffs.map { tariffDto ->
            var price = calculateExactTripPrice(
                tariffId = tariffDto.id!!,
                polyline = polyline,
                totalDistanceMeters = totalMeters,
                serviceIds = emptyList(),
                addedValue = 0.0,
                waypointsCount = waypointsCount,
                originLat = originLat,
                originLng = originLng,
                isDebug = false
            )

            var oldPrice: Double? = null

            if (client != null && !hasActiveDiscount) {
                var discountAmount = 0.0

                if (isPromoValid && activePromoUsage != null) {
                    val percent = activePromoUsage.promoCode.discountPercent
                    var calcDiscount = kotlin.math.round(price * (percent / 100.0))
                    val maxAmount = activePromoUsage.promoCode.maxDiscountAmount
                    if (maxAmount != null && calcDiscount > maxAmount) {
                        calcDiscount = maxAmount
                    }
                    discountAmount = calcDiscount
                } else if (activeReward != null) {
                    val task = activeReward.promoTask
                    val percent = task.discountPercent
                    var calcDiscount = kotlin.math.round(price * (percent / 100.0))
                    if (task.maxDiscountAmount != null && calcDiscount > task.maxDiscountAmount!!) {
                        calcDiscount = task.maxDiscountAmount!!
                    }
                    discountAmount = calcDiscount
                }

                if (activeRegPlan != null && activeRegPlan.discountPercent != null) {
                    val percent = activeRegPlan.discountPercent!!
                    var calcDiscount = kotlin.math.round(price * (percent / 100.0))
                    val maxAmount = activeRegPlan.maxDiscountAmount
                    if (maxAmount != null && calcDiscount > maxAmount) {
                        calcDiscount = maxAmount
                    }
                    if (calcDiscount > discountAmount) {
                        discountAmount = calcDiscount
                    }
                }

                if (activeFreeMinPlan != null) {
                    discountAmount += tariffDto.basePrice ?: 0.0
                }

                if (discountAmount > 0.0) {
                    oldPrice = price
                    price -= discountAmount
                    if (price < 1.0) price = 1.0
                }
            }

            CarTariffDto(
                id = tariffDto.id,
                name = tariffDto.name,
                basePrice = tariffDto.basePrice,
                pricePerKm = tariffDto.pricePerKm,
                pricePerKmOutCity = tariffDto.pricePerKmOutCity,
                extraWaypointPrice = tariffDto.extraWaypointPrice,
                freeWaitingMinutes = tariffDto.freeWaitingMinutes,
                pricePerWaitingMinute = tariffDto.pricePerWaitingMinute,
                isActive = tariffDto.isActive,
                imageUrl = tariffDto.imageUrl,
                isBeta = tariffDto.isBeta,
                isUnavailable = tariffDto.isUnavailable,
                calculatedPrice = price,
                description = tariffDto.description,
                oldPrice = oldPrice
            )
        }
    }

    @Transactional
    fun createOrder(client: Client, request: CreateOrderRequestDto): TaxiOrderDto {
        if (client.userPhone.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Для замовлення таксі необхідно прив'язати номер телефону")
        }

        if (request.addedValue < 0.0 || (request.boostAmount ?: 0.0) < 0.0) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Надбавка не може бути від'ємною")
        }

        logger.info(">>> СОЗДАНИЕ ЗАКАЗА: From='${request.fromAddress}'")
        val tariff = tariffRepository.findById(request.tariffId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Тариф не знайдено") }

        if (!tariff.isActive) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Тариф недоступний")
        }

        // 1. Захист від подвійного кліку (Рейс-кондишн лок на 3 секунди для клієнта)
        val lockKey = "lock:create_order:${client.id}"
        val isLocked = redisTemplate.opsForValue().setIfAbsent(lockKey, "true", 3, java.util.concurrent.TimeUnit.SECONDS)
        if (isLocked == false) {
            throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Будь ласка, зачекайте, ваше замовлення вже обробляється")
        }

        // 2. Перевірка ліміту активних замовлень із захистом від "мертвих душ"
        val clientActiveOrdersKey = "client:active_orders:${client.id}"
        var activeOrdersCount = redisTemplate.opsForSet().size(clientActiveOrdersKey) ?: 0L

        if (activeOrdersCount >= 3) {
            val cachedIdsRaw = redisTemplate.opsForSet().members(clientActiveOrdersKey) ?: emptySet<Any>()
            val cachedIds = cachedIdsRaw.mapNotNull { it.toString().toLongOrNull() }
            if (cachedIds.isNotEmpty()) {
                val realActiveOrders = orderRepository.findAllById(cachedIds).filter {
                    it.status != OrderStatus.COMPLETED && it.status != OrderStatus.CANCELLED
                }
                if (realActiveOrders.size < cachedIds.size) {
                    redisTemplate.delete(clientActiveOrdersKey)
                    if (realActiveOrders.isNotEmpty()) {
                        val realIds = realActiveOrders.map { it.id.toString() }.toTypedArray()
                        redisTemplate.opsForSet().add(clientActiveOrdersKey, *realIds)
                    }
                    activeOrdersCount = realActiveOrders.size.toLong()
                }
            }
        }

        if (activeOrdersCount >= 3) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Ви не можете мати більше 3 активних замовлень одночасно")
        }

        // --- Валідація часу ---
        if (request.scheduledAt != null) {
            val now = LocalDateTime.now()
            val minValidTime = now.plusMinutes(40)

            if (request.scheduledAt.isBefore(minValidTime)) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Попереднє замовлення можна зробити мінімум за 40 хвилин до подачі"
                )
            }

            if (request.scheduledAt.isAfter(now.plusDays(7))) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Не можна бронювати більше ніж на 7 днів наперед"
                )
            }
        }

        val waypointsCount = request.waypoints?.size ?: 0

        // Защита от подмены нулевой дистанции клиентом (сверяем с прямой линией по Гаверсинусу)
        val minStraightMeters = if (
            request.originLat != null && request.originLng != null &&
            request.destLat != null && request.destLng != null &&
            request.originLat != 0.0 && request.destLat != 0.0
        ) {
            GeometryUtils.calculateDistanceMeters(request.originLat, request.originLng, request.destLat, request.destLng).toInt()
        } else 0
        val verifiedDistanceMeters = maxOf(request.distanceMeters, minStraightMeters)

        // --- Розрахунок базової ціни ---
        val calculatedPrice = calculateExactTripPrice(
            tariffId = request.tariffId,
            polyline = request.googleRoutePolyline,
            totalDistanceMeters = verifiedDistanceMeters,
            serviceIds = request.serviceIds,
            addedValue = request.addedValue,
            waypointsCount = waypointsCount,
            originLat = request.originLat,
            originLng = request.originLng,
            isDebug = true
        )

        var discountAmount = 0.0
        var isPromoCodeUsedForThisOrder = false
        var promoCodeApplied = false

        val hasActiveDiscount = orderRepository.hasActiveOrderWithDiscount(client.id!!)
        var activePlan: com.taxiapp.server.model.promo.PromoPlan? = null

        if (!hasActiveDiscount) {
            activePlan = promoService.findActiveFreeMinPlan(client)
            if (activePlan != null) {
                discountAmount += tariff.basePrice
            }

            val activeRegPlan = promoService.findActiveRegistrationDiscountPlan(client)
            if (activeRegPlan != null && activeRegPlan.discountPercent != null) {
                val percent = activeRegPlan.discountPercent!!
                var calcDiscount = kotlin.math.round(calculatedPrice * (percent / 100.0))
                if (activeRegPlan.maxDiscountAmount != null && calcDiscount > activeRegPlan.maxDiscountAmount!!) {
                    calcDiscount = activeRegPlan.maxDiscountAmount!!
                }
                if (calcDiscount > discountAmount) {
                    discountAmount = calcDiscount
                    activePlan = activeRegPlan
                }
            }

            val activePromoUsage = promoCodeService.findActiveUsage(client)
            if (activePromoUsage != null) {
                val isExpired = activePromoUsage.expiresAt != null && LocalDateTime.now().isAfter(activePromoUsage.expiresAt)
                if (!isExpired) {
                    val percent = activePromoUsage.promoCode.discountPercent
                    var calcDiscount = kotlin.math.round(calculatedPrice * (percent / 100.0))
                    val maxAmount = activePromoUsage.promoCode.maxDiscountAmount
                    if (maxAmount != null && calcDiscount > maxAmount) {
                        calcDiscount = maxAmount
                    }
                    discountAmount = calcDiscount
                    promoCodeApplied = true
                    isPromoCodeUsedForThisOrder = true
                }
            }

            if (!promoCodeApplied) {
                val activeReward = promoService.findActiveReward(client)
                if (activeReward != null) {
                    val task = activeReward.promoTask
                    val percent = task.discountPercent
                    var calcDiscount = kotlin.math.round(calculatedPrice * (percent / 100.0))
                    if (task.maxDiscountAmount != null && calcDiscount > task.maxDiscountAmount!!) {
                        calcDiscount = task.maxDiscountAmount!!
                    }
                    discountAmount = calcDiscount
                    isPromoCodeUsedForThisOrder = false
                }
            }
        } else {
            logger.info(">>> [Discount] Создание заказа для клиента #${client.id} без скидки: уже есть активный заказ со скидкой.")
        }

        val fullPrice = calculatedPrice

        var clientPayAmount = fullPrice - discountAmount
        if (clientPayAmount < 1.0) {
            clientPayAmount = 1.0
        }

        val actualDiscountApplied = fullPrice - clientPayAmount

        val destSector = if (request.destLat != null && request.destLng != null) {
            sectorService.findSectorByCoordinates(request.destLat, request.destLng)
        } else null

        val originSector = if (request.originLat != null && request.originLng != null) {
            sectorService.findSectorByCoordinates(request.originLat, request.originLng)
        } else null

        val initialStatus = if (request.scheduledAt != null) OrderStatus.SCHEDULED else OrderStatus.REQUESTED

        val newOrder = TaxiOrder(
            client = client,
            fromAddress = request.fromAddress,
            toAddress = request.toAddress,
            status = initialStatus,
            createdAt = LocalDateTime.now(),
            tariff = tariff,
            price = fullPrice,
            appliedDiscount = actualDiscountApplied,
            isPromoCodeUsed = isPromoCodeUsedForThisOrder,
            originLat = request.originLat ?: 0.0,
            originLng = request.originLng ?: 0.0,
            destLat = request.destLat ?: 0.0,
            destLng = request.destLng ?: 0.0,
            googleRoutePolyline = request.googleRoutePolyline,
            distanceMeters = verifiedDistanceMeters,
            durationSeconds = max(0, request.durationSeconds),
            tariffName = tariff.name,
            comment = request.comment,
            paymentMethod = request.paymentMethod,
            addedValue = max(0.0, request.addedValue),
            destinationSector = destSector,
            originSector = originSector,
            scheduledAt = request.scheduledAt
        )

        if (activePlan != null) {
            newOrder.promoPlanId = activePlan.id
        }

        newOrder.districtStart = request.districtStart
        newOrder.districtEnd = request.districtEnd
        newOrder.basePrice = tariff.basePrice
        newOrder.boostAdded = max(0.0, request.boostAmount ?: request.addedValue)
        newOrder.clientDeviceId = request.deviceId ?: client.deviceId
        newOrder.finalPrice = fullPrice

        if (!request.serviceIds.isNullOrEmpty()) {
            val services = taxiServiceRepository.findAllById(request.serviceIds)
            newOrder.selectedServices.addAll(services)
        }

        if (!request.waypoints.isNullOrEmpty()) {
            val stopsList = request.waypoints.mapIndexed { index, wpDto ->
                OrderStop(
                    address = wpDto.address,
                    lat = wpDto.lat,
                    lng = wpDto.lng,
                    stopOrder = index + 1,
                    order = newOrder
                )
            }
            newOrder.stops.addAll(stopsList)
        }

        if (initialStatus == OrderStatus.SCHEDULED) {
            logger.info("Order scheduled for ${newOrder.scheduledAt}")
            val savedScheduled = orderRepository.save(newOrder)
            redisTemplate.opsForSet().add(clientActiveOrdersKey, savedScheduled.id.toString())
            broadcastOrderChange(savedScheduled, "ADD")
            return TaxiOrderDto(savedScheduled)
        }

        var savedOrder = orderRepository.save(newOrder)
        redisTemplate.opsForSet().add(clientActiveOrdersKey, savedOrder.id.toString())

        if (savedOrder.paymentMethod == "CARD") {
            val token = client.cardToken
            if (token.isNullOrEmpty()) {
                throw ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "У вас немає прив'язаної картки для оплати")
            }
            val paymentOrderId = "ride_${savedOrder.id}"

            val isHoldSuccess = liqPayService.holdWithToken(
                orderId = paymentOrderId,
                amount = clientPayAmount,
                cardToken = token,
                description = "Резервування коштів за поїздку UNIT #${savedOrder.id}"
            )

            if (!isHoldSuccess) {
                throw ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "Недостатньо коштів на картці або помилка банку")
            }

            savedOrder.authorizedAmount = clientPayAmount
            savedOrder = orderRepository.save(savedOrder)
        }

        if (initialStatus == OrderStatus.SCHEDULED) {
            logger.info("Order scheduled for ${savedOrder.scheduledAt}")
            broadcastOrderChange(savedOrder, "ADD")
            return TaxiOrderDto(savedOrder)
        }

        val rejectedIds = savedOrder.rejectedDriverIds
        val pickupLat = savedOrder.originLat ?: 0.0
        val pickupLng = savedOrder.originLng ?: 0.0

        // 1. ЛАНЦЮГ (Chain)
        val activeInProgressOrders = orderRepository.findAllByStatus(OrderStatus.IN_PROGRESS)
        val chainDriver: Driver? = activeInProgressOrders.asSequence()
            .mapNotNull { it.driver }
            .filter { drv ->
                if (rejectedIds.contains(drv.id)) return@filter false

                val isTariffActive = if (drv.selectedTariffs.isEmpty()) {
                    drv.allowedTariffs.contains(savedOrder.tariff)
                } else {
                    drv.selectedTariffs.contains(savedOrder.tariff)
                }
                if (!isTariffActive) return@filter false

                val meta = redisTemplate.opsForHash<String, Any>().get("orders:active_drivers", drv.id.toString())
                if (meta == null) return@filter false

                val currentOrder = activeInProgressOrders.firstOrNull { it.driver?.id == drv.id } ?: return@filter false
                val distToPickup = GeometryUtils.calculateDistance(currentOrder.destLat ?: 0.0, currentOrder.destLng ?: 0.0, pickupLat, pickupLng)

                distToPickup <= drv.searchRadius
            }
            .minByOrNull { drv ->
                val currentOrder = activeInProgressOrders.first { it.driver?.id == drv.id }
                GeometryUtils.calculateDistance(currentOrder.destLat ?: 0.0, currentOrder.destLng ?: 0.0, pickupLat, pickupLng)
            }

        if (chainDriver != null) {
            logger.info(">>> CHAIN driver found via Memory Pipeline: ${chainDriver.id}")
            assignOrderToDriver(savedOrder, chainDriver)
            savedOrder.assignmentType = "CHAIN"
            savedOrder = orderRepository.save(savedOrder)
            return TaxiOrderDto(savedOrder)
        }

        val nearbyDrivers = getEnrichedNearbyDrivers(pickupLat, pickupLng, maxRadiusKm = 10.0, tariff = savedOrder.tariff)

        // 2. AUTO/CYCLE - УМНЫЙ ПОИСК
        val autoDriver = findDriverByAutoFilter(savedOrder, nearbyDrivers)

        if (autoDriver != null) {
            logger.info(">>> FILTER driver selected via Memory Pipeline: ${autoDriver.id}")
            val matchedFilter = filterRepository.findAllByDriverId(autoDriver.id!!).firstOrNull { it.isActive }
            savedOrder.assignmentType = when {
                matchedFilter?.isCycle == true -> "CYCLE"
                matchedFilter?.fromType == "HOME" -> "HOME"
                else -> "AUTO"
            }
            assignOrderToDriver(savedOrder, autoDriver)
            return TaxiOrderDto(savedOrder)
        }

        // 3. ETHER / SMART CANDIDATE
        val candidates = nearbyDrivers.filter { drv ->
            if (rejectedIds.contains(drv.id)) return@filter false
            if (drv.activityScore <= -1) return@filter false

            val dist = GeometryUtils.calculateDistance(drv.latitude ?: 0.0, drv.longitude ?: 0.0, pickupLat, pickupLng)
            if (dist > drv.searchRadius) return@filter false

            when (drv.searchMode) {
                com.taxiapp.server.model.enums.DriverSearchMode.CHAIN -> true
                com.taxiapp.server.model.enums.DriverSearchMode.HOME -> {
                    drv.homeRidesLeft > 0 && drv.homeSectors.any { it.id == destSector?.id }
                }
                else -> false
            }
        }

        if (candidates.isNotEmpty()) {
            val bestDriver = candidates.minByOrNull { drv ->
                estimateDrivingTimeSeconds(drv.latitude!!, drv.longitude!!, pickupLat, pickupLng)
            }!!
            logger.info(">>> SMART Selection via Redis GEO: Winner ${bestDriver.id} from ${candidates.size} candidates.")
            assignOrderToDriver(savedOrder, bestDriver)
            savedOrder = orderRepository.save(savedOrder)
        } else {
            logger.info(">>> No candidates found via Redis. Sending directly to Ether.")
            savedOrder.status = OrderStatus.REQUESTED
            savedOrder.assignmentType = "ETHER"
            savedOrder = orderRepository.save(savedOrder)
            broadcastOrderChange(savedOrder, "ADD")
        }

        return TaxiOrderDto(savedOrder)
    }

    private fun assignOrderToDriver(order: TaxiOrder, driver: Driver) {
        val dist = calculateDistanceKm(
            driver.latitude ?: 0.0,
            driver.longitude ?: 0.0,
            order.originLat!!,
            order.originLng!!
        )
        logger.info(">>> Assigning order ${order.id} to driver ${driver.id} (Distance: ${String.format("%.2f", dist)} km)")

        val expiresAt = LocalDateTime.now().plusSeconds(20)
        order.status = OrderStatus.OFFERING
        order.offeredDriver = driver
        order.offerExpiresAt = expiresAt

        val epochSecond = expiresAt.toEpochSecond(java.time.ZoneOffset.UTC)
        redisTemplate.opsForZSet().add("orders:expired_offers", order.id.toString(), epochSecond.toDouble())

        notificationService.sendOrderOffer(driver, order)
        broadcastOrderChange(order, "UPDATE")
    }

    private fun selectFastestDriver(drivers: List<Driver>, targetLat: Double, targetLng: Double): Driver {
        return drivers.minByOrNull { driver ->
            estimateDrivingTimeSeconds(driver.latitude!!, driver.longitude!!, targetLat, targetLng)
        } ?: drivers.first()
    }

    private fun estimateDrivingTimeSeconds(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val distKm = calculateDistanceKm(lat1, lon1, lat2, lon2)
        val realDistKm = distKm * 1.4
        val speedKmh = 30.0
        return (realDistKm / speedKmh) * 3600
    }

    private fun findDriverByAutoFilter(order: TaxiOrder, nearbyDrivers: List<Driver>): Driver? {
        val driverIds = nearbyDrivers.mapNotNull { it.id }
        if (driverIds.isEmpty()) return null

        val activeFilters = filterRepository.findAllByDriverIdInAndIsActiveTrue(driverIds)
            .filter { it.isAuto || it.isCycle }

        val matchedFilter = activeFilters.firstOrNull { filter ->
            val driver = nearbyDrivers.firstOrNull { it.id == filter.driver.id } ?: return@firstOrNull false
            if (order.rejectedDriverIds.contains(driver.id)) return@firstOrNull false
            matchesFilter(order, filter, driver)
        }

        return matchedFilter?.driver
    }

    private fun calculateDistanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }

    @Transactional
    fun rejectOffer(driver: Driver, orderId: Long) {
        val order = orderRepository.findByIdWithLock(orderId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }

        if (order.status == OrderStatus.OFFERING && order.offeredDriver?.id == driver.id) {
            logger.info("Водій ${driver.id} відхилив пропозицію ${order.id}")

            redisTemplate.opsForZSet().remove("orders:expired_offers", order.id.toString())

            driverActivityService.updateScore(driver, -50, "Відмова від запропонованого замовлення #${order.id}")

            order.rejectedDriverIds.add(driver.id!!)
            order.status = OrderStatus.REQUESTED
            order.offeredDriver = null
            order.offerExpiresAt = null

            val saved = orderRepository.save(order)
            broadcastOrderChange(saved, "ADD")
        }
    }

    @Scheduled(fixedRate = 2000)
    @Transactional
    fun checkExpiredOffers() {
        val nowEpoch = java.time.Instant.now().epochSecond.toDouble()
        val expiredIds = redisTemplate.opsForZSet().rangeByScore("orders:expired_offers", 0.0, nowEpoch) ?: emptySet()

        if (expiredIds.isEmpty()) return

        for (idStr in expiredIds) {
            val idClean = idStr.toString()

            val removedCount = redisTemplate.opsForZSet().remove("orders:expired_offers", idClean)
            if (removedCount == null || removedCount <= 0L) {
                continue
            }

            val orderId = idClean.toLongOrNull() ?: continue
            val order = orderRepository.findById(orderId).orElse(null) ?: continue

            if (order.status == OrderStatus.OFFERING) {
                logger.info("Час пропозиції замовлення ${order.id} вичерпано (Redis ZSET). Штраф і переход в Ефір.")

                order.offeredDriver?.let {
                    driverActivityService.updateScore(it, -50, "Пропущено замовлення #${order.id}")
                    order.rejectedDriverIds.add(it.id!!)
                }

                order.status = OrderStatus.REQUESTED
                order.offeredDriver = null
                order.offerExpiresAt = null

                val saved = orderRepository.save(order)
                broadcastOrderChange(saved, "ADD")
            }
        }
    }

    fun broadcastOrderChange(order: TaxiOrder, action: String) {
        val orderUuidStr = order.uuid.toString()
        val orderDto = TaxiOrderDto(order)

        val message = OrderSocketMessage(action, orderUuidStr, if (action == "REMOVE") null else orderDto)

        sendSocketAfterCommit("/topic/admin/orders", message)
        sendSocketAfterCommit("/topic/clients/${order.client.id}/orders", message)

        if (order.status == OrderStatus.REQUESTED) {
            sendSocketAfterCommit("/topic/drivers/ether", message)
        } else {
            sendSocketAfterCommit("/topic/drivers/ether", OrderSocketMessage("REMOVE", orderUuidStr, null))

            val targetDriver = order.driver ?: order.offeredDriver
            targetDriver?.id?.let { driverId ->
                sendSocketAfterCommit("/topic/drivers/$driverId/orders", message)
            }
        }
    }

    fun getDriverHeatmap(): List<HeatmapZoneDto> {
        val activeOrders = orderRepository.findAllByStatus(OrderStatus.REQUESTED)
        if (activeOrders.isEmpty()) return emptyList()

        val R = 0.0112
        val VERT_STEP = 1.5 * R
        val HORIZ_BASE = Math.sqrt(3.0) * R

        val grouped = activeOrders.groupBy { order ->
            val lat = order.originLat!!
            val lng = order.originLng!!
            val row = Math.round(lat / VERT_STEP).toInt()
            val rowCenterLat = row * VERT_STEP
            val lngStep = HORIZ_BASE / Math.cos(Math.toRadians(rowCenterLat))
            val offset = if (row % 2 != 0) lngStep / 2.0 else 0.0
            val col = Math.round((lng - offset) / lngStep).toInt()
            Pair(row, col)
        }

        val zones = mutableListOf<HeatmapZoneDto>()
        for ((gridKey, ordersInCell) in grouped) {
            val (row, col) = gridKey
            val count = ordersInCell.size
            if (count < 1) continue

            val level = when {
                count >= 10 -> 3
                count >= 5 -> 2
                else -> 1
            }

            val centerLat = row * VERT_STEP
            val lngStep = HORIZ_BASE / Math.cos(Math.toRadians(centerLat))
            val offset = if (row % 2 != 0) lngStep / 2.0 else 0.0
            val centerLng = (col * lngStep) + offset

            zones.add(HeatmapZoneDto(centerLat, centerLng, count, level))
        }
        return zones
    }

    fun getFilteredOrdersForDriver(driver: Driver): List<TaxiOrderDto> {
        val meta = redisTemplate.opsForHash<String, Any>().get("drivers:meta", driver.id.toString()) as? Map<*, *>
        val isOnlineRedis = (meta?.get("isOnline") as? String)?.toBoolean()
            ?: (meta?.get("isOnline") as? Boolean)
            ?: false

        val isOnline = driver.isOnline || isOnlineRedis
        if (!isOnline) {
            logger.info(">>> [ETHER] Водій #${driver.id} офлайн (DB=${driver.isOnline}, Redis=$isOnlineRedis). Ефір порожній.")
            return emptyList()
        }

        if (driver.activityScore <= 0) {
            logger.info(">>> [ETHER] Водій #${driver.id} заблокований за низьку активність (${driver.activityScore}).")
            return emptyList()
        }

        if (driver.latitude == null || driver.longitude == null || driver.latitude == 0.0) {
            val rLat = (meta?.get("lat") as? String)?.toDoubleOrNull() ?: (meta?.get("lat") as? Double)
            val rLng = (meta?.get("lng") as? String)?.toDoubleOrNull() ?: (meta?.get("lng") as? Double)
            if (rLat != null && rLng != null) {
                driver.latitude = rLat
                driver.longitude = rLng
            }
        }

        val allOrders = orderRepository.findAllAvailableForEther()

        val driverTariffIds = if (driver.selectedTariffs.isNotEmpty()) {
            driver.selectedTariffs.mapNotNull { it.id }.toSet()
        } else {
            driver.allowedTariffs.mapNotNull { it.id }.toSet()
        }

        val availableOrdersByTariff = allOrders.filter { order ->
            driverTariffIds.isEmpty() ||
                driverTariffIds.contains(order.tariff.id) ||
                order.tariff.name.equals("Стандарт", ignoreCase = true)
        }

        val activeFilters = filterRepository.findAllByDriverId(driver.id!!)
            .filter { it.isActive }

        if (activeFilters.isEmpty()) {
            return availableOrdersByTariff.map { TaxiOrderDto(it) }
        }

        val filtered = availableOrdersByTariff.filter { order ->
            activeFilters.any { filter -> matchesFilter(order, filter, driver) }
        }

        return filtered.map { TaxiOrderDto(it) }
    }

    private fun matchesFilter(order: TaxiOrder, filter: DriverFilter, driver: Driver): Boolean {
        if (filter.fromType == "DISTANCE") {
            val dLat = driver.latitude
            val dLng = driver.longitude
            if (dLat != null && dLng != null && dLat != 0.0 && dLng != 0.0) {
                val distToOrder = GeometryUtils.calculateDistance(
                    dLat, dLng,
                    order.originLat ?: 0.0, order.originLng ?: 0.0
                )
                val maxDist = filter.fromDistance ?: 30.0
                if (distToOrder > maxDist) return false
            }
        } else {
            if (filter.fromSectors.isNotEmpty()) {
                val startSectors = sectorService.getCachedSectorEntities().filter { it.id in filter.fromSectors }
                val isInStartSector = startSectors.any { sector ->
                    GeometryUtils.isPointInPolygon(order.originLat ?: 0.0, order.originLng ?: 0.0, sector.points)
                }
                if (!isInStartSector) return false
            }
        }

        if (filter.toSectors.isNotEmpty()) {
            val endSectors = sectorService.getCachedSectorEntities().filter { it.id in filter.toSectors }
            val isInEndSector = endSectors.any { sector ->
                GeometryUtils.isPointInPolygon(order.destLat ?: 0.0, order.destLng ?: 0.0, sector.points)
            }
            if (!isInEndSector) return false
        }

        if (filter.paymentType != "ANY" && filter.paymentType != null) {
            if (order.paymentMethod != filter.paymentType) return false
        }

        val totalKm = (order.distanceMeters ?: 0) / 1000.0
        if (filter.tariffType == "SIMPLE") {
            if (order.price < (filter.minPrice ?: 0.0)) return false
            if (totalKm > 0) {
                val pricePerKm = order.price / totalKm
                if (pricePerKm < (filter.minPricePerKm ?: 0.0)) return false
            }
        } else {
            val minPrice = filter.complexMinPrice ?: 0.0
            val kmInMin = filter.complexKmInMin ?: 0.0
            val priceCity = filter.complexPriceKmCity ?: 0.0

            val requiredPrice = if (totalKm <= kmInMin) {
                minPrice
            } else {
                minPrice + (totalKm - kmInMin) * priceCity
            }
            if (order.price < requiredPrice) return false
        }
        return true
    }

    fun findActiveOrderByDriver(driver: Driver): TaxiOrderDto? {
        val activeStatuses = listOf(
            OrderStatus.ACCEPTED,
            OrderStatus.DRIVER_ARRIVED,
            OrderStatus.IN_PROGRESS,
            OrderStatus.SCHEDULED,
            OrderStatus.ARRIVED_AT_WAYPOINT
        )
        val activeOrder = orderRepository.findAllByDriverId(driver.id!!)
            .filter { it.status in activeStatuses }
            .firstOrNull()

        if (activeOrder != null) {
            return TaxiOrderDto(activeOrder)
        }

        val offeredOrder = orderRepository.findAllByStatus(OrderStatus.OFFERING)
            .find { it.offeredDriver?.id == driver.id }

        return if (offeredOrder != null) {
            if (offeredOrder.offerExpiresAt != null && LocalDateTime.now().isAfter(offeredOrder.offerExpiresAt)) {
                null
            } else {
                TaxiOrderDto(offeredOrder)
            }
        } else {
            null
        }
    }

    fun findHistoryByDriver(driver: Driver): List<TaxiOrderDto> {
        val orders = orderRepository.findAllByDriverId(driver.id!!)
            .filter { it.status == OrderStatus.COMPLETED || it.status == OrderStatus.CANCELLED }
            .sortedByDescending { it.id }

        if (orders.isEmpty()) return emptyList()

        val orderIds = orders.mapNotNull { it.id }

        val activityMap = activityHistoryRepository.findAllByDriverIdAndOrderIdIn(driver.id!!, orderIds)
            .filter { it.orderId != null }
            .associateBy { it.orderId!! }

        return orders.map { order ->
            val realPointsChange = activityMap[order.id]?.pointsChange ?: 0
            TaxiOrderDto(order).copy(activityBonus = realPointsChange)
        }
    }

    fun getOrderById(id: Long): TaxiOrderDto {
        val order = orderRepository.findById(id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено") }
        return TaxiOrderDto(order)
    }

    fun getClientHistory(client: Client, page: Int = 0, size: Int = 30): List<TaxiOrderDto> {
        val pageable = org.springframework.data.domain.PageRequest.of(page, size)
        val orders = orderRepository.findAllOrdersForClientHistory(
            clientId = client.id!!,
            phone = client.userPhone,
            pageable = pageable
        )
        return orders.map { TaxiOrderDto(it) }
    }

    @Transactional
    fun cancelOrder(user: User, orderId: Long, reasonText: String? = null): TaxiOrderDto {
        val order = orderRepository.findByIdWithLock(orderId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено") }

        if (order.client.id != user.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Доступ до чужого замовлення заборонено")
        }

        if (order.status == OrderStatus.COMPLETED || order.status == OrderStatus.CANCELLED) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Замовлення вже закрите")
        }

        val evosUid = order.evosOrderUid
        if (order.isSentToEvos && !evosUid.isNullOrEmpty()) {
            val cancelledInEvos = evoSService.cancelOrderInEvoS(evosUid)
            if (cancelledInEvos) {
                order.isSentToEvos = false
                order.evosCancelPending = false
                order.evosOrderUid = null
                logger.info(">>> [EvoS] Заказ $evosUid успешно отменен в EvoS")
            } else {
                order.evosCancelPending = true
                logger.warn(">>> [EvoS] Партнер не подтвердил отмену $evosUid. Заказ помечен для Retry-отмены.")
            }
        }

        redisTemplate.opsForZSet().remove("orders:expired_offers", order.id.toString())
        order.driver?.let { drv ->
            redisTemplate.opsForHash<String, Any>().delete("orders:active_drivers", drv.id.toString())
        }

        if (order.paymentMethod == "CARD" && order.authorizedAmount > 0.0) {
            val paymentOrderId = "ride_${order.id}"
            val acceptedTime = order.acceptedAt
            val now = LocalDateTime.now()

            if (acceptedTime == null || ChronoUnit.MINUTES.between(acceptedTime, now) < 3) {
                logger.info("Клиент отменил вовремя или машина не найдена. Полный возврат холда.")
                liqPayService.voidFunds(paymentOrderId)
            } else {
                val penaltyAmount = order.tariff.basePrice
                logger.info("Прошло больше 3 минут. Списываем стоимость подачи: $penaltyAmount UAH")

                liqPayService.captureFunds(paymentOrderId, penaltyAmount)

                val commissionSetting = appSettingRepository.findById("driver_commission_percent").orElse(null)
                val commissionPercent = commissionSetting?.value?.toDoubleOrNull() ?: 10.0
                val commissionAmount = penaltyAmount * (commissionPercent / 100.0)
                val driverPayout = penaltyAmount - commissionAmount

                order.driver?.let { driver ->
                    driver.balance += driverPayout
                    driverRepository.save(driver)

                    val compTransaction = com.taxiapp.server.model.finance.WalletTransaction(
                        driver = driver,
                        amount = driverPayout,
                        operationType = com.taxiapp.server.model.enums.TransactionType.DEPOSIT,
                        orderId = order.id,
                        description = "Компенсація за скасування замовлення після 3 хв подачі (#${order.id})"
                    )
                    walletTransactionRepository.save(compTransaction)
                }
            }
            order.authorizedAmount = 0.0
        }

        val assignedDriver = order.driver
        if (assignedDriver != null && (
                order.status == OrderStatus.OFFERING ||
                order.status == OrderStatus.ACCEPTED ||
                order.status == OrderStatus.DRIVER_ARRIVED ||
                order.status == OrderStatus.IN_PROGRESS ||
                order.status == OrderStatus.ARRIVED_AT_WAYPOINT
            )
        ) {
            notificationService.sendOrderCancelToDriver(assignedDriver, order)
        }

        if (reasonText != null) {
            order.cancellationReason = reasonText
        }

        order.status = OrderStatus.CANCELLED
        order.completedAt = LocalDateTime.now()
        order.cancelledAt = LocalDateTime.now()
        redisTemplate.opsForSet().remove("client:active_orders:${order.client.id}", order.id.toString())

        val saved = orderRepository.save(order)
        chatService.clearChatForOrder(orderId)

        broadcastOrderChange(saved, "REMOVE")

        return TaxiOrderDto(saved)
    }

    @Transactional
    fun driverCancelOrder(driver: Driver, orderId: Long, reasonId: Long?): TaxiOrderDto {
        val order = orderRepository.findByIdWithLock(orderId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено") }

        if (order.driver?.id != driver.id) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Це не ваше замовлення")

        if (order.status == OrderStatus.COMPLETED || order.status == OrderStatus.CANCELLED) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Замовлення вже закрите")
        }

        redisTemplate.opsForHash<String, Any>().delete("orders:active_drivers", driver.id.toString())

        var penalty = 50
        var reasonText = "Не вказано"

        if (reasonId != null) {
            val reasonObj = cancellationReasonRepository.findById(reasonId).orElse(null)
            if (reasonObj != null) {
                penalty = reasonObj.penaltyScore
                reasonText = reasonObj.reasonText
            }
        }

        if (order.status == OrderStatus.SCHEDULED) {
            logger.info("Driver ${driver.id} rejected SCHEDULED order #${order.id}. Returning to Ether.")

            val adaptivePenalty = if (order.isDriverConfirmed == true) 50 else 30
            val adaptiveText = if (order.isDriverConfirmed == true) {
                "$reasonText (Скасовано підтверджене заплановане)"
            } else {
                "$reasonText (Скасовано заплановане)"
            }

            driverActivityService.processOrderCancellation(driver, orderId, adaptivePenalty, adaptiveText)

            order.rejectedDriverIds.add(driver.id!!)
            order.driver = null
            order.isDriverConfirmed = false
            order.confirmationRequestedAt = null
            order.offeredDriver = null

            val saved = orderRepository.save(order)
            broadcastOrderChange(saved, "ADD")

            return TaxiOrderDto(saved)
        }

        // КРИТИЧЕСКИЙ ФИКС: Если водитель отменяет заказ с оплатой картой — размораживаем холд клиента в LiqPay!
        if (order.paymentMethod == "CARD" && order.authorizedAmount > 0.0) {
            val paymentOrderId = "ride_${order.id}"
            liqPayService.voidFunds(paymentOrderId)
            order.authorizedAmount = 0.0
            logger.info(">>> [DriverCancel] Холд картки клієнта розблоковано для замовлення #${order.id}")
        }

        order.cancellationReason = reasonText
        driverActivityService.processOrderCancellation(driver, orderId, penalty, reasonText)
        redisTemplate.opsForSet().remove("client:active_orders:${order.client.id}", order.id.toString())

        order.status = OrderStatus.CANCELLED
        order.completedAt = LocalDateTime.now()
        order.cancelledAt = LocalDateTime.now()
        val saved = orderRepository.save(order)

        redisTemplate.opsForValue().set(
            "client:last_completed_order:${order.client.id}",
            saved.id.toString(),
            10,
            java.util.concurrent.TimeUnit.MINUTES
        )

        chatService.clearChatForOrder(orderId)

        broadcastOrderChange(saved, "REMOVE")
        return TaxiOrderDto(saved)
    }

    @Transactional
    fun acceptOrder(driver: Driver, orderId: Long): TaxiOrderDto {
        if (driver.photoControlRestricted) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Ви обмежені в роботі до успішного фотоконтролю")
        }
        if (!driver.isOnline) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Переключіть режим на Онлайн")
        }
        if (driver.activityScore <= 0) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Низька активність.")
        }

        val order = orderRepository.findByIdWithLock(orderId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено") }

        if (order.status == OrderStatus.OFFERING) {
            if (order.offeredDriver?.id != driver.id) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "Зайнято")
            }
            redisTemplate.opsForZSet().remove("orders:expired_offers", order.id.toString())
        } else if (order.status == OrderStatus.SCHEDULED) {
            if (order.driver != null) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "Вже має водія")
            }
        } else if (order.status != OrderStatus.REQUESTED) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Замовлення вже зайняте")
        }

        val evosUid = order.evosOrderUid
        if (order.isSentToEvos && !evosUid.isNullOrEmpty()) {
            logger.info(">>> [OrderService] Водитель #${driver.id} принимает заказ #${order.id}. Отзываем из EvoS (UID: $evosUid)...")
            val cancelledInEvos = evoSService.cancelOrderInEvoS(evosUid)
            if (cancelledInEvos) {
                order.isSentToEvos = false
                order.evosCancelPending = false
                order.evosOrderUid = null
            } else {
                order.evosCancelPending = true
                logger.warn(">>> [OrderService] EvoS не подтвердил отмену $evosUid. Передан в фоновый Retry.")
            }
        }

        order.driver = driver
        order.offeredDriver = null
        order.acceptedAt = LocalDateTime.now()

        if (driver.latitude != null && driver.longitude != null && order.originLat != null && order.originLng != null) {
            order.driverToPickupPolyline = fetchDirectionsPolyline(
                startLat = driver.latitude!!,
                startLng = driver.longitude!!,
                endLat = order.originLat!!,
                endLng = order.originLng!!
            )
        }

        if (order.status != OrderStatus.SCHEDULED) {
            order.status = OrderStatus.ACCEPTED
        } else {
            logger.info("Driver ${driver.id} reserved scheduled order ${order.id}")
        }
        val saved = orderRepository.save(order)

        if (saved.status != OrderStatus.SCHEDULED) {
            redisTemplate.opsForHash<String, Any>().put("orders:active_drivers", driver.id.toString(), saved.uuid.toString())
        }

        broadcastOrderChange(saved, "UPDATE")

        if (saved.status == OrderStatus.ACCEPTED) {
            notificationService.sendOrderStatusToClient(
                token = saved.client.fcmToken,
                orderId = saved.id!!,
                status = saved.status.name,
                title = "Водій знайдено",
                body = "Водій прийняв ваше замовлення та прямує до вас."
            )
        }

        return TaxiOrderDto(saved)
    }

    fun findActiveOrRecentlyCompletedOrder(client: Client): TaxiOrderDto? {
        val clientActiveOrdersKey = "client:active_orders:${client.id}"
        val activeIdsRaw = redisTemplate.opsForSet().members(clientActiveOrdersKey) ?: emptySet<Any>()
        val activeIds = activeIdsRaw.mapNotNull { it.toString().toLongOrNull() }

        if (activeIds.isNotEmpty()) {
            val activeStatuses = listOf(
                OrderStatus.REQUESTED,
                OrderStatus.OFFERING,
                OrderStatus.ACCEPTED,
                OrderStatus.DRIVER_ARRIVED,
                OrderStatus.IN_PROGRESS,
                OrderStatus.ARRIVED_AT_WAYPOINT,
                OrderStatus.SCHEDULED
            )
            val activeOrders = orderRepository.findAllById(activeIds)
                .filter { it.status in activeStatuses }

            if (activeOrders.isNotEmpty()) {
                val currentOrder = activeOrders.maxByOrNull { it.id ?: 0L }
                if (currentOrder != null) {
                    return TaxiOrderDto(currentOrder)
                }
            } else {
                redisTemplate.delete(clientActiveOrdersKey)
            }
        }

        val lastFinishedKey = "client:last_completed_order:${client.id}"
        val lastFinishedIdStr = redisTemplate.opsForValue().get(lastFinishedKey)?.toString()
        if (!lastFinishedIdStr.isNullOrBlank()) {
            val finishedId = lastFinishedIdStr.toLongOrNull()
            if (finishedId != null) {
                val finishedOrder = orderRepository.findById(finishedId).orElse(null)
                if (finishedOrder != null) {
                    if (finishedOrder.isRatedByClient) {
                        redisTemplate.delete(lastFinishedKey)
                        return null
                    }
                    if (finishedOrder.status == OrderStatus.CANCELLED) {
                        // Отмененный водителем заказ показываем один раз и сразу убираем из Redis
                        redisTemplate.delete(lastFinishedKey)
                        return TaxiOrderDto(finishedOrder)
                    }
                    if (finishedOrder.status == OrderStatus.COMPLETED) {
                        // НЕ удаляем ключ при GET-чтении! Он удалится при вызове /dismiss или после оценки,
                        // чтобы повторный syncActiveOrder() в onResume не затер экран завершения через 204 No Content.
                        return TaxiOrderDto(finishedOrder)
                    }
                }
            }
            redisTemplate.delete(lastFinishedKey)
        }

        return null
    }

    fun dismissCompletedOrder(client: Client, orderId: Long) {
        val lastFinishedIdStr = redisTemplate.opsForValue().get("client:last_completed_order:${client.id}")?.toString()
        val targetId = if (orderId != 0L) orderId else (lastFinishedIdStr?.toLongOrNull() ?: 0L)

        redisTemplate.delete("client:last_completed_order:${client.id}")
        logger.info(">>> [OrderService] Завершене замовлення #$targetId очищено з Redis для клієнта #${client.id}")
        // ВАЖНО: Не ставим здесь order.isRatedByClient = true, так как после нажатия "Зрозуміло"
        // у клиента открывается диалог оценки водителя (POST /api/v1/client/rate).
    }

    @Transactional
    fun driverArrived(driver: Driver, orderId: Long): TaxiOrderDto {
        val order = orderRepository.findByIdWithLock(orderId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено") }

        if (order.driver?.id != driver.id) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Це не ваше замовлення")
        if (order.status != OrderStatus.ACCEPTED) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Невірний статус")

        order.status = OrderStatus.DRIVER_ARRIVED
        order.arrivedAt = LocalDateTime.now()

        val savedOrder = orderRepository.save(order)
        broadcastOrderChange(savedOrder, "UPDATE")

        notificationService.sendOrderStatusToClient(
            token = savedOrder.client.fcmToken,
            orderId = savedOrder.id!!,
            status = savedOrder.status.name,
            title = "Водій на місці",
            body = "Ваше таксі прибуло, виходьте."
        )

        return TaxiOrderDto(savedOrder)
    }

    @Transactional
    fun startTrip(driver: Driver, orderId: Long): TaxiOrderDto {
        val order = orderRepository.findByIdWithLock(orderId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено") }

        if (order.driver?.id != driver.id) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Це не ваше замовлення")
        if (order.status != OrderStatus.ACCEPTED && order.status != OrderStatus.DRIVER_ARRIVED) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Не можна почати поїздку")
        }

        order.status = OrderStatus.IN_PROGRESS
        order.startedAt = LocalDateTime.now()

        if (order.arrivedAt != null) {
            val waitingStart = if (order.scheduledAt != null && order.arrivedAt!!.isBefore(order.scheduledAt)) {
                order.scheduledAt!!
            } else {
                order.arrivedAt!!
            }

            val minutesWaited = maxOf(0, ChronoUnit.MINUTES.between(waitingStart, order.startedAt).toInt())
            val freeMinutes = order.tariff.freeWaitingMinutes

            if (minutesWaited > freeMinutes) {
                val paidMinutes = minutesWaited - freeMinutes
                val extraCost = paidMinutes * order.tariff.pricePerWaitingMinute

                order.waitingPrice = extraCost
                order.price += extraCost

                logger.info("Paid waiting applied for Order ${order.id}: $paidMinutes mins. Extra cost: $extraCost")
            }
        }

        val savedOrder = orderRepository.save(order)
        broadcastOrderChange(savedOrder, "UPDATE")

        notificationService.sendOrderStatusToClient(
            token = savedOrder.client.fcmToken,
            orderId = savedOrder.id!!,
            status = savedOrder.status.name,
            title = "В дорозі",
            body = "Поїздка розпочалася. Приємної дороги!"
        )

        return TaxiOrderDto(savedOrder)
    }

    fun fetchDirectionsPolyline(startLat: Double, startLng: Double, endLat: Double, endLng: Double): String? {
        return try {
            val osrmUrl = "https://router.project-osrm.org/route/v1/driving/$startLng,$startLat;$endLng,$endLat?overview=full&geometries=polyline"
            val connection = java.net.URL(osrmUrl).openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 4000
            connection.readTimeout = 4000
            connection.setRequestProperty("User-Agent", "TaxiServer/1.0")

            if (connection.responseCode == 200) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val json = com.google.gson.JsonParser.parseString(responseText).asJsonObject
                val routes = json.getAsJsonArray("routes")
                if (routes != null && routes.size() > 0) {
                    val polyline = routes[0].asJsonObject.get("geometry")?.asString
                    logger.info(">>> [OSRM Router] Маршрут подачі успішно згенеровано (довжина рядка: ${polyline?.length})")
                    polyline
                } else null
            } else {
                logger.warn(">>> [OSRM Router] Помилка відповіді OSRM: код ${connection.responseCode}")
                null
            }
        } catch (e: Exception) {
            logger.error(">>> [OSRM Router] Помилка запиту до OSRM: ${e.message}")
            null
        }
    }

    @Transactional
    fun confirmScheduledOrder(driver: Driver, orderId: Long): TaxiOrderDto {
        val order = orderRepository.findByIdWithLock(orderId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }

        if (order.driver?.id != driver.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Це не ваше замовлення")
        }
        if (order.status != OrderStatus.SCHEDULED) {
            return TaxiOrderDto(order)
        }

        logger.info("Driver ${driver.id} CONFIRMED scheduled order #${order.id}")

        order.isDriverConfirmed = true
        order.status = OrderStatus.ACCEPTED

        val saved = orderRepository.save(order)

        // ФИКС: Замовлення активоване, записуємо активного водія в Redis Hash (було помилкове .delete)
        redisTemplate.opsForHash<String, Any>().put("orders:active_drivers", driver.id.toString(), saved.uuid.toString())

        broadcastOrderChange(saved, "ADD")
        return TaxiOrderDto(saved)
    }

    @Transactional(noRollbackFor = [ResponseStatusException::class])
    fun completeOrder(driver: Driver, orderId: Long): TaxiOrderDto {
        val order = orderRepository.findByIdWithLock(orderId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено") }

        if (order.driver?.id != driver.id) throw ResponseStatusException(HttpStatus.FORBIDDEN)

        if (order.status == OrderStatus.COMPLETED || order.status == OrderStatus.CANCELLED) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Замовлення вже завершено або скасовано")
        }

        redisTemplate.opsForHash<String, Any>().delete("orders:active_drivers", driver.id.toString())

        val currentClientPayAmount = max(1.0, order.price - order.appliedDiscount)
        var cardCapturedAmount = 0.0
        var unpaidCashDelta = 0.0

        // =======================================================
        // 💳 ФИНАНСОВЫЙ БЛОК: ЗАКРЫТИЕ ХОЛДА И ДОСПИСАНИЕ ПРОСТОЯ
        // =======================================================
        if (order.paymentMethod == "CARD") {
            val paymentOrderId = "ride_${order.id}"

            if (currentClientPayAmount <= order.authorizedAmount) {
                val isSuccess = liqPayService.captureFunds(paymentOrderId, currentClientPayAmount)

                if (!isSuccess) {
                    order.paymentMethod = "CASH"
                    orderRepository.save(order)
                    broadcastOrderChange(order, "UPDATE")
                    throw ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "Помилка завершення платежу. Спосіб змінено на ГОТІВКУ.")
                }
                cardCapturedAmount = currentClientPayAmount
                logger.info(">>> SUCCESS: Capture processed for exact client amount: $currentClientPayAmount")
            } else {
                val isCaptureSuccess = liqPayService.captureFunds(paymentOrderId, order.authorizedAmount)

                if (!isCaptureSuccess) {
                    order.paymentMethod = "CASH"
                    orderRepository.save(order)
                    broadcastOrderChange(order, "UPDATE")
                    throw ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "Помилка списання базової суми холда. Переведено на ГОТІВКУ.")
                }
                // Базовый холд УСПЕШНО списан с карты клиента! Фиксируем эту сумму!
                cardCapturedAmount = order.authorizedAmount

                val delta = currentClientPayAmount - order.authorizedAmount
                val token = order.client.cardToken

                val isDeltaSuccess = if (!token.isNullOrEmpty()) {
                    val deltaOrderId = "ride_delta_${order.id}_${System.currentTimeMillis()}"
                    liqPayService.payWithToken(
                        orderId = deltaOrderId,
                        amount = delta,
                        cardToken = token,
                        description = "Доплата за простій замовлення #${order.id}"
                    )
                } else false

                if (isDeltaSuccess) {
                    cardCapturedAmount += delta
                    logger.info(">>> SUCCESS: Hold captured and excess delta ($delta UAH) charged successfully!")
                } else {
                    // ФИКС ПОТЕРИ ДЕНЕГ: Не затираем order.price = delta!
                    // Завершаем заказ штатно, начисляем водителю уже списанный cardCapturedAmount (authorizedAmount),
                    // а разницу за простой (delta) водитель берет с клиента наличными.
                    unpaidCashDelta = delta
                    order.comment = ((order.comment ?: "") + " [Доплата готівкою за простій: $delta грн]").trim()

                    notificationService.sendOrderStatusToClient(
                        token = order.client.fcmToken,
                        orderId = order.id!!,
                        status = OrderStatus.COMPLETED.name,
                        title = "Доплата за простій",
                        body = "Основна сума (${order.authorizedAmount.toInt()} грн) списана з картки. Будь ласка, доплатіть водію готівкою за простій: ${delta.toInt()} грн."
                    )
                }
            }
        }

        order.status = OrderStatus.COMPLETED
        order.completedAt = LocalDateTime.now()
        order.finalPrice = order.price
        redisTemplate.opsForSet().remove("client:active_orders:${order.client.id}", order.id.toString())

        if (order.startedAt != null) {
            val realDurationSeconds = java.time.Duration.between(order.startedAt, order.completedAt).toSeconds().toInt()
            order.durationSeconds = if (realDurationSeconds > 0) realDurationSeconds else 1
        }

        driver.completedRides += 1
        driverActivityService.processOrderCompletion(driver, order)
        order.client.tripsCount += 1
        order.client.totalCompletedOrders += 1
        clientRepository.save(order.client)

        // =======================================================
        // 💰 РАСЧЕТ И СПИСАНИЕ КОМИССИИ ВОДИТЕЛЯ (С ПОЛНОЙ СУММЫ)
        // =======================================================
        val commissionSetting = appSettingRepository.findById("driver_commission_percent").orElse(null)
        val commissionPercent = commissionSetting?.value?.toDoubleOrNull() ?: 10.0

        val commissionAmount = order.price * (commissionPercent / 100.0)
        driver.balance = driver.balance - commissionAmount

        if (order.paymentMethod == "CARD" && cardCapturedAmount > 0.0) {
            driver.balance = driver.balance + cardCapturedAmount

            val rideTransaction = com.taxiapp.server.model.finance.WalletTransaction(
                driver = driver,
                amount = cardCapturedAmount,
                operationType = com.taxiapp.server.model.enums.TransactionType.DEPOSIT,
                orderId = order.id,
                description = if (unpaidCashDelta > 0.0) {
                    "Безготівкова оплата замовлення #${order.id} (решта ${unpaidCashDelta.toInt()} грн готівкою)"
                } else {
                    "Безготівкова оплата замовлення #${order.id}"
                }
            )
            walletTransactionRepository.save(rideTransaction)
        }

        order.commissionAmount = commissionAmount

        if (order.paymentMethod == "CARD") {
            order.bankCommissionAmount = 0.0
            order.payoutAmount = cardCapturedAmount - commissionAmount
        }

        val transaction = com.taxiapp.server.model.finance.WalletTransaction(
            driver = driver,
            amount = -commissionAmount,
            operationType = com.taxiapp.server.model.enums.TransactionType.COMMISSION,
            orderId = order.id,
            description = "Комісія ${String.format("%.1f", commissionPercent)}% за замовлення #${order.id}"
        )
        walletTransactionRepository.save(transaction)

        // =======================================================
        // 🎁 МАРКЕТИНГОВАЯ ДОПЛАТА ВОДИТЕЛЮ ЗА СКИДКУ
        // =======================================================
        if (order.appliedDiscount > 0.0) {
            val marketingWalletSetting = appSettingRepository.findById("company_marketing_balance").orElse(null)
            val marketingBalance = marketingWalletSetting?.value?.toDoubleOrNull() ?: 0.0

            val clientPays = (order.price - order.appliedDiscount).toInt()
            val subsidy = order.appliedDiscount.toInt()
            driverPayoutService.createPayout(
                driver = driver,
                order = order,
                amount = order.appliedDiscount,
                comment = "Доплата водію за знижку клієнта (Клієнт: ${clientPays} грн, Доплата: ${subsidy} грн)"
            )

            if (marketingBalance >= order.appliedDiscount) {
                marketingWalletSetting?.value = (marketingBalance - order.appliedDiscount).toString()
                appSettingRepository.save(marketingWalletSetting)

                driver.balance = driver.balance + order.appliedDiscount

                val compensationTx = com.taxiapp.server.model.finance.WalletTransaction(
                    driver = driver,
                    amount = order.appliedDiscount,
                    operationType = com.taxiapp.server.model.enums.TransactionType.BONUS,
                    orderId = order.id,
                    description = "Доплата за знижку клієнта #" + order.id
                ).apply {
                    status = com.taxiapp.server.model.enums.TransactionStatus.COMPLETED
                }

                walletTransactionRepository.save(compensationTx)
                logger.info(">>> MARKETING COMPENSATION: Paid ${order.appliedDiscount} UAH to driver ${driver.id}")
            } else {
                val pendingTx = com.taxiapp.server.model.finance.WalletTransaction(
                    driver = driver,
                    amount = order.appliedDiscount,
                    operationType = com.taxiapp.server.model.enums.TransactionType.BONUS,
                    orderId = order.id,
                    description = "Доплата за знижку клієнта #${order.id} (очікує фінансування комп.)"
                ).apply {
                    status = com.taxiapp.server.model.enums.TransactionStatus.PENDING
                }

                walletTransactionRepository.save(pendingTx)
                logger.warn(">>> MARKETING FUNDS DEPLETED: Compensation of ${order.appliedDiscount} UAH is PENDING for driver ${driver.id}")
            }
        }

        driverRepository.save(driver)

        val filters = filterRepository.findAllByDriverId(driver.id!!)
        for (f in filters) {
            if (f.isActive && f.isAuto) {
                f.isAuto = false
                if (!f.isEther && !f.isCycle) f.isActive = false
                filterRepository.save(f)
            }
        }

        if (order.appliedDiscount > 0.0) {
            if (order.promoPlanId != null) {
                promoService.markPromoPlanAsUsed(order.client, order.promoPlanId!!)
            } else if (order.isPromoCodeUsed) {
                val activePromoUsage = promoCodeService.findActiveUsage(order.client)
                activePromoUsage?.let { promoCodeService.markAsUsed(it.id) }
            } else {
                promoService.markRewardAsUsed(order.client)
            }
        }

        promoService.updateProgressOnRideCompletion(order.client, order)

        val savedOrder = orderRepository.save(order)
        chatService.clearChatForOrder(orderId)

        redisTemplate.opsForValue().set(
            "client:last_completed_order:${order.client.id}",
            savedOrder.id.toString(),
            10,
            java.util.concurrent.TimeUnit.MINUTES
        )

        notificationService.sendOrderStatusToClient(
            token = savedOrder.client.fcmToken,
            orderId = savedOrder.id!!,
            status = savedOrder.status.name,
            title = "Поїздку завершено",
            body = "Дякуємо за поїздку! Оцініть, будь ласка, водія."
        )

        broadcastOrderChange(savedOrder, "UPDATE")

        return TaxiOrderDto(savedOrder)
    }

    @Scheduled(fixedRate = 60000)
    @Transactional
    fun activateScheduledOrders() {
        val now = LocalDateTime.now()
        val checkThreshold = now.plusMinutes(35)

        val pendingOrders = orderRepository.findAllByStatusAndScheduledAtBefore(OrderStatus.SCHEDULED, checkThreshold)

        for (order in pendingOrders) {
            if (order.scheduledAt == null) continue

            if (order.isSentToEvos && order.isEvosDriverAssigned) {
                logger.info(">>> [OrderService] Заплановане замовлення #${order.id} вже закріплене за водієм EvoS (${order.evosDriverCarInfo})")
                if (order.scheduledAt!!.isBefore(now.plusMinutes(30)) && order.status == OrderStatus.SCHEDULED) {
                    order.status = OrderStatus.ACCEPTED
                    orderRepository.save(order)
                    broadcastOrderChange(order, "UPDATE")
                }
                continue
            }

            if (order.scheduledAt!!.isBefore(now.minusHours(1))) {
                logger.warn("Scheduled order ${order.id} expired. Cancelling.")
                if (order.paymentMethod == "CARD" && order.authorizedAmount > 0.0) {
                    liqPayService.voidFunds("ride_${order.id}")
                    order.authorizedAmount = 0.0
                }
                order.status = OrderStatus.CANCELLED
                order.completedAt = now
                order.cancelledAt = now
                redisTemplate.opsForSet().remove("client:active_orders:${order.client.id}", order.id.toString())
                orderRepository.save(order)

                chatService.clearChatForOrder(order.id!!)
                continue
            }

            if (order.scheduledAt!!.isAfter(now.plusMinutes(31))) {
                continue
            }

            if (order.driver != null) {
                if (order.isDriverConfirmed == true) {
                    if (order.status == OrderStatus.SCHEDULED) {
                        order.status = OrderStatus.ACCEPTED
                        orderRepository.save(order)
                        broadcastOrderChange(order, "ADD")
                    }
                    continue
                }

                if (order.confirmationRequestedAt == null) {
                    logger.info("Asking confirmation for order #${order.id}")
                    order.confirmationRequestedAt = LocalDateTime.now()
                    orderRepository.save(order)

                    notificationService.sendConfirmationRequest(order.driver!!, order)
                } else {
                    if (now.isAfter(order.confirmationRequestedAt!!.plusMinutes(1))) {
                        logger.warn("Driver ${order.driver!!.id} failed to confirm order #${order.id}. PENALTY applied.")

                        driverActivityService.processOrderCancellation(order.driver!!, order.id!!, 50, "Не підтвердив заплановане")

                        order.rejectedDriverIds.add(order.driver!!.id!!)
                        order.driver = null
                        order.isDriverConfirmed = false
                        order.confirmationRequestedAt = null

                        val saved = orderRepository.save(order)
                        broadcastOrderChange(saved, "ADD")
                    }
                }
            } else {
                logger.info("Searching driver for scheduled order #${order.id}")

                val scheduledNearby = getEnrichedNearbyDrivers(order.originLat ?: 0.0, order.originLng ?: 0.0, maxRadiusKm = 10.0, tariff = order.tariff)

                val bestDriver = scheduledNearby.filter { drv ->
                    !order.rejectedDriverIds.contains(drv.id) && drv.searchMode != com.taxiapp.server.model.enums.DriverSearchMode.OFFLINE
                }.minByOrNull { drv ->
                    estimateDrivingTimeSeconds(drv.latitude!!, drv.longitude!!, order.originLat ?: 0.0, order.originLng ?: 0.0)
                }

                if (bestDriver != null) {
                    logger.info("Offering scheduled order #${order.id} to ${bestDriver.id}")
                    order.status = OrderStatus.OFFERING
                    order.offeredDriver = bestDriver
                    val expiresAt = LocalDateTime.now().plusSeconds(20)
                    order.offerExpiresAt = expiresAt

                    val epochSecond = expiresAt.toEpochSecond(java.time.ZoneOffset.UTC)
                    redisTemplate.opsForZSet().add("orders:expired_offers", order.id.toString(), epochSecond.toDouble())

                    notificationService.sendOrderOffer(bestDriver, order)
                    orderRepository.save(order)
                } else {
                    if (order.status != OrderStatus.REQUESTED) {
                        order.status = OrderStatus.REQUESTED
                        orderRepository.save(order)
                        broadcastOrderChange(order, "ADD")
                    }
                }
            }
        }
    }

    private fun getEnrichedNearbyDrivers(
        pickupLat: Double,
        pickupLng: Double,
        maxRadiusKm: Double = 10.0,
        tariff: com.taxiapp.server.model.order.CarTariff? = null
    ): List<Driver> {
        val circle = org.springframework.data.geo.Circle(
            org.springframework.data.geo.Point(pickupLng, pickupLat),
            org.springframework.data.geo.Distance(maxRadiusKm, org.springframework.data.geo.Metrics.KILOMETERS)
        )
        val args = org.springframework.data.redis.connection.RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
            .includeCoordinates().sortAscending()

        val geoResults = redisTemplate.opsForGeo().radius("drivers:geo", circle, args) ?: return emptyList()

        val driverIds = geoResults.content.mapNotNull { "${it.content.name}".toLongOrNull() }
        if (driverIds.isEmpty()) return emptyList()

        val driversWithActiveOffers = orderRepository.findAllByStatus(OrderStatus.OFFERING)
            .mapNotNull { it.offeredDriver?.id }
            .toSet()

        val driversFromDb = driverRepository.findAllById(driverIds).associateBy { it.id }

        return geoResults.content.mapNotNull { result ->
            val id = "${result.content.name}".toLongOrNull() ?: return@mapNotNull null

            if (driversWithActiveOffers.contains(id)) return@mapNotNull null

            val driver = driversFromDb[id] ?: return@mapNotNull null
            if (driver.isBlocked) return@mapNotNull null

            val isTariffActive = if (driver.selectedTariffs.isEmpty()) {
                driver.allowedTariffs.contains(tariff)
            } else {
                driver.selectedTariffs.contains(tariff)
            }
            if (tariff != null && !isTariffActive) return@mapNotNull null

            val meta = redisTemplate.opsForHash<String, Any>().get("drivers:meta", id.toString()) as? Map<*, *>
            if (meta != null) {
                val isOnline = (meta["isOnline"] as? String)?.toBoolean() ?: false
                if (!isOnline) return@mapNotNull null

                driver.isOnline = true
                driver.latitude = result.content.point.y
                driver.longitude = result.content.point.x
                driver.bearing = (meta["bearing"] as? String)?.toFloat() ?: 0f
                driver.searchMode = com.taxiapp.server.model.enums.DriverSearchMode.valueOf(meta["status"] as? String ?: "MANUAL")
            } else {
                return@mapNotNull null
            }
            driver
        }
    }

    private fun blockCoordinateRounding(v: Double): Double = v

    @Transactional
    fun updatePaymentMethod(user: User, orderId: Long, method: String) {
        val order = orderRepository.findByIdWithLock(orderId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено") }

        if (order.client.id != user.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Доступ до чужого замовлення заборонено")
        }
        if (order.status == OrderStatus.COMPLETED || order.status == OrderStatus.CANCELLED) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Неможливо змінити спосіб оплати закритого замовлення")
        }
        val normalizedMethod = method.uppercase().trim()
        if (normalizedMethod !in listOf("CASH", "CARD", "DRIVER_CARD")) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Недопустимий спосіб оплати")
        }

        val previousMethod = order.paymentMethod.uppercase().trim()
        if (previousMethod == normalizedMethod) {
            return
        }

        // 1. Если переключаемся НА оплату картой (CARD) — проверяем токен и холдируем средства
        if (normalizedMethod == "CARD" && order.authorizedAmount <= 0.0) {
            val token = order.client.cardToken
            if (token.isNullOrEmpty()) {
                throw ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "У вас немає прив'язаної картки для оплати")
            }
            val clientPayAmount = max(1.0, order.price - order.appliedDiscount)
            val paymentOrderId = "ride_${order.id}"
            val isHoldSuccess = liqPayService.holdWithToken(
                orderId = paymentOrderId,
                amount = clientPayAmount,
                cardToken = token,
                description = "Резервування коштів за поїздку UNIT #${order.id}"
            )
            if (!isHoldSuccess) {
                throw ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "Недостатньо коштів на картці або помилка банку")
            }
            order.authorizedAmount = clientPayAmount
        }
        // 2. Если переключаемся С карты (CARD) на наличные (CASH) или на карту водителю (DRIVER_CARD) — снимаем холд
        else if (previousMethod == "CARD" && normalizedMethod != "CARD" && order.authorizedAmount > 0.0) {
            val paymentOrderId = "ride_${order.id}"
            liqPayService.voidFunds(paymentOrderId)
            order.authorizedAmount = 0.0
            logger.info(">>> [PaymentSwitch] Холд картки розблоковано при зміні оплати на $normalizedMethod (Замовлення #${order.id})")
        }

        order.paymentMethod = normalizedMethod
        val savedOrder = orderRepository.save(order)
        broadcastOrderChange(savedOrder, "UPDATE")
    }

    @Transactional
    fun updatePrice(user: User, orderId: Long, addedValue: Double) {
        if (addedValue < 0.0 || addedValue > 10000.0) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Некоректна сума надбавки")
        }

        val order = orderRepository.findByIdWithLock(orderId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено") }

        if (order.client.id != user.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Доступ до чужого замовлення заборонено")
        }
        if (order.status != OrderStatus.REQUESTED && order.status != OrderStatus.SCHEDULED && order.status != OrderStatus.OFFERING) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Ціну можна змінювати тільки під час пошуку авто")
        }

        // Разрешаем клиенту как увеличивать надбавку, так и возвращать ее обратно (вплоть до 0.0 — базовой цены маршрута),
        // пока заказ находится в поиске и еще не принят водителем.
        val basePriceWithoutExtra = order.price - order.addedValue
        order.addedValue = addedValue
        order.price = max(order.tariff.basePrice, basePriceWithoutExtra + addedValue)
        order.boostAdded = addedValue
        if (addedValue > 0.0) {
            order.boostClickedAt = LocalDateTime.now()
        }
        order.finalPrice = order.price

        val savedOrder = orderRepository.save(order)

        if (savedOrder.isSentToEvos && !savedOrder.evosOrderUid.isNullOrBlank()) {
            evoSService.updateOrderPriceInEvoS(savedOrder)
        }

        broadcastOrderChange(savedOrder, "UPDATE")
    }

    fun getActiveOrdersForDispatcher(): List<TaxiOrderDto> {
        val activeStatuses = listOf(
            OrderStatus.REQUESTED,
            OrderStatus.OFFERING,
            OrderStatus.ACCEPTED,
            OrderStatus.DRIVER_ARRIVED,
            OrderStatus.IN_PROGRESS,
            OrderStatus.SCHEDULED,
            OrderStatus.ARRIVED_AT_WAYPOINT
        )
        return orderRepository.findAllByStatusIn(activeStatuses)
            .map { TaxiOrderDto(it) }
            .sortedByDescending { it.id }
    }

    private fun sendSocketAfterCommit(destination: String, message: Any) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() {
                    messagingTemplate.convertAndSend(destination, message)
                }
            })
        } else {
            messagingTemplate.convertAndSend(destination, message)
        }
    }

    @Transactional
    fun arriveAtWaypoint(driver: Driver, orderId: Long): TaxiOrderDto {
        val order = orderRepository.findByIdWithLock(orderId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено") }

        if (order.driver?.id != driver.id) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Це не ваше замовлення")
        if (order.status != OrderStatus.IN_PROGRESS) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Не можна відзначити прибуття на точку в цьому статусі")
        }

        order.status = OrderStatus.ARRIVED_AT_WAYPOINT
        order.currentStopOrder += 1
        order.waypointArrivedAt = LocalDateTime.now()

        val savedOrder = orderRepository.save(order)
        broadcastOrderChange(savedOrder, "UPDATE")

        val currentStopAddress = savedOrder.stops.sortedBy { it.stopOrder }.find { it.stopOrder == savedOrder.currentStopOrder }?.address ?: ""
        notificationService.sendOrderStatusToClient(
            token = savedOrder.client.fcmToken,
            orderId = savedOrder.id!!,
            status = savedOrder.status.name,
            title = "Водій на проміжній точці",
            body = "Очікуйте, водій прибув на зупинку: $currentStopAddress"
        )

        return TaxiOrderDto(savedOrder)
    }

    @Transactional
    fun resumeTrip(driver: Driver, orderId: Long): TaxiOrderDto {
        val order = orderRepository.findByIdWithLock(orderId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено") }

        if (order.driver?.id != driver.id) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Це не ваше замовлення")
        if (order.status != OrderStatus.ARRIVED_AT_WAYPOINT) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Поїздку неможливо продовжити з цього стану")
        }

        if (order.waypointArrivedAt != null) {
            val minutesWaited = maxOf(0, ChronoUnit.MINUTES.between(order.waypointArrivedAt!!, LocalDateTime.now()).toInt())
            val freeMinutes = order.tariff.freeWaitingMinutes

            if (minutesWaited > freeMinutes) {
                val paidMinutes = minutesWaited - freeMinutes
                val extraCost = paidMinutes * order.tariff.pricePerWaitingMinute

                order.waitingPrice += extraCost
                order.price += extraCost
                logger.info("[WAYPOINT_WAITING] Заказ ${order.id}: платное ожидание $paidMinutes мин. Добавлено: $extraCost UAH")
            }
        }

        order.status = OrderStatus.IN_PROGRESS
        order.waypointArrivedAt = null

        val savedOrder = orderRepository.save(order)
        broadcastOrderChange(savedOrder, "UPDATE")

        notificationService.sendOrderStatusToClient(
            token = savedOrder.client.fcmToken,
            orderId = savedOrder.id!!,
            status = savedOrder.status.name,
            title = "Рушаємо далі",
            body = "Поїздка продовжилась."
        )

        return TaxiOrderDto(savedOrder)
    }

    fun mapToDto(order: TaxiOrder): TaxiOrderDto = TaxiOrderDto(order)
}