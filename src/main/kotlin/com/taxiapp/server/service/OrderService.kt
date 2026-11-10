package com.taxiapp.server.service

import com.taxiapp.server.dto.driver.HeatmapZoneDto
import com.taxiapp.server.dto.order.CreateOrderRequestDto
import com.taxiapp.server.dto.order.OrderSocketMessage
import com.taxiapp.server.dto.order.RoutePointDto
import com.taxiapp.server.dto.order.RouteRequestDto
import com.taxiapp.server.dto.order.RouteResponseDto
import com.taxiapp.server.dto.order.TaxiOrderDto
import com.taxiapp.server.dto.tariff.CarTariffDto
import com.taxiapp.server.model.enums.DriverSearchMode
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
import org.springframework.data.domain.PageRequest
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
import kotlin.math.cos
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

    // L1 In-Memory микро-кэш доступных заказов Эфира (защита от Thundering Herd при 10 000+ водителей онлайн)
    @Volatile
    private var cachedEtherOrders: List<TaxiOrder> = emptyList()

    @Volatile
    private var etherCacheExpiresAtMs: Long = 0L

    private fun invalidateEtherCache() {
        etherCacheExpiresAtMs = 0L
    }

    private fun getCachedAvailableEtherOrders(): List<TaxiOrder> {
        val now = System.currentTimeMillis()
        val current = cachedEtherOrders
        if (now < etherCacheExpiresAtMs) {
            return current
        }
        val fresh = orderRepository.findAllAvailableForEther()
        cachedEtherOrders = fresh
        etherCacheExpiresAtMs = now + 1_500L
        return fresh
    }

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

        var servicesCost = 0.0
        if (!serviceIds.isNullOrEmpty()) {
            val services = taxiServiceRepository.findAllById(serviceIds)
            servicesCost = services.sumOf { it.price }
        }

        val waypointsCost = waypointsCount * tariff.extraWaypointPrice

        if (polyline.isNullOrEmpty() && totalDistanceMeters <= 0) {
            return max(tariff.basePrice, ceil(tariff.basePrice + servicesCost + safeAddedValue + waypointsCost))
        }

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

        val routePrice = (billableKmCity * tariff.pricePerKm) + (billableKmOutCity * tariff.pricePerKmOutCity)

        val (pickupLat, pickupLng) = if (originLat != null && originLng != null) {
            Pair(originLat, originLng)
        } else {
            GeometryUtils.getFirstPointFromPolyline(polyline) ?: Pair(null, null)
        }

        val surgeMultiplier = surgePricingService.calculateCurrentSurgeMultiplier(pickupLat, pickupLng)
        val surgedTripBase = (tariff.basePrice + routePrice) * surgeMultiplier

        var finalPrice = surgedTripBase + servicesCost + safeAddedValue + waypointsCost
        finalPrice = ceil(finalPrice)
        return max(finalPrice, tariff.basePrice)
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

        val hasActiveDiscount = if (client != null) orderRepository.hasActiveOrderWithDiscount(client.id!!) else true
        val activePromoUsage = if (client != null && !hasActiveDiscount) promoCodeService.findActiveUsage(client) else null
        val isPromoValid = activePromoUsage != null && (activePromoUsage.expiresAt == null || !LocalDateTime.now().isAfter(activePromoUsage.expiresAt))
        val activeReward = if (client != null && !hasActiveDiscount && !isPromoValid) promoService.findActiveReward(client) else null
        val activeRegPlan = if (client != null && !hasActiveDiscount) promoService.findActiveRegistrationDiscountPlan(client) else null
        val activeFreeMinPlan = if (client != null && !hasActiveDiscount) promoService.findActiveFreeMinPlan(client) else null

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

        val lockKey = "lock:create_order:${client.id}"
        val isLocked = redisTemplate.opsForValue().setIfAbsent(lockKey, "true", 3, java.util.concurrent.TimeUnit.SECONDS)
        if (isLocked == false) {
            throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Будь ласка, зачекайте, ваше замовлення вже обробляється")
        }

        try {
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

            val minStraightMeters = if (
                request.originLat != null && request.originLng != null &&
                request.destLat != null && request.destLng != null &&
                request.originLat != 0.0 && request.destLat != 0.0
            ) {
                GeometryUtils.calculateDistanceMeters(request.originLat, request.originLng, request.destLat, request.destLng).toInt()
            } else 0
            val verifiedDistanceMeters = maxOf(request.distanceMeters, minStraightMeters)

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

            var savedOrder = orderRepository.save(newOrder)

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

            redisTemplate.opsForSet().add(clientActiveOrdersKey, savedOrder.id.toString())

            if (initialStatus == OrderStatus.SCHEDULED) {
                logger.info("Order scheduled for ${savedOrder.scheduledAt}")
                broadcastOrderChange(savedOrder, "ADD")
                return TaxiOrderDto(savedOrder)
            }

            // Запускаем умный конвейер диспетчеризации (Chain -> AutoFilter -> Smart Candidate -> Ether)
            savedOrder = dispatchOrderToNextCandidate(savedOrder)
            return TaxiOrderDto(savedOrder)
        } catch (ex: Exception) {
            redisTemplate.delete(lockKey)
            throw ex
        }
    }

    /**
     * Единый конвейер подбора водителя (используется и при создании заказа, и при переназначении после отказа/таймаута).
     */
    private fun dispatchOrderToNextCandidate(order: TaxiOrder): TaxiOrder {
        val rejectedIds = order.rejectedDriverIds
        val pickupLat = order.originLat ?: 0.0
        val pickupLng = order.originLng ?: 0.0
        val destSector = order.destinationSector

        // 1. ЛАНЦЮГ (Chain) — Ищем только поездки IN_PROGRESS, чей финиш находится в bounding-box вокруг подачи (~30 км)
        if (pickupLat != 0.0 && pickupLng != 0.0) {
            val latDelta = 0.28 // ~31 км
            val lngDelta = 0.28 / max(0.2, cos(Math.toRadians(pickupLat)))
            val candidateInProgressOrders = orderRepository.findChainCandidateOrdersInBounds(
                minLat = pickupLat - latDelta,
                maxLat = pickupLat + latDelta,
                minLng = pickupLng - lngDelta,
                maxLng = pickupLng + lngDelta
            )

            if (candidateInProgressOrders.isNotEmpty()) {
                val activeDriverIdsStr = candidateInProgressOrders.mapNotNull { it.driver?.id?.toString() }
                val activeDriverMetaList = if (activeDriverIdsStr.isNotEmpty()) {
                    redisTemplate.opsForHash<String, Any>().multiGet("orders:active_drivers", activeDriverIdsStr)
                } else emptyList()
                val activeDriversInRedis = activeDriverIdsStr.zip(activeDriverMetaList)
                    .filter { it.second != null }
                    .mapNotNull { it.first.toLongOrNull() }
                    .toSet()

                val bestChainMatch = candidateInProgressOrders.asSequence()
                    .mapNotNull { activeOrder ->
                        val drv = activeOrder.driver ?: return@mapNotNull null
                        if (drv.id == null || rejectedIds.contains(drv.id)) return@mapNotNull null
                        if (drv.isBlocked || drv.photoControlRestricted || drv.activityScore <= 0) return@mapNotNull null
                        if (!isDriverTariffMatching(drv, order.tariff)) return@mapNotNull null
                        if (!activeDriversInRedis.contains(drv.id)) return@mapNotNull null

                        val distToPickup = GeometryUtils.calculateDistance(
                            activeOrder.destLat ?: 0.0,
                            activeOrder.destLng ?: 0.0,
                            pickupLat,
                            pickupLng
                        )
                        if (distToPickup <= drv.searchRadius) Pair(drv, distToPickup) else null
                    }
                    .minByOrNull { it.second }

                if (bestChainMatch != null) {
                    val chainDriver = bestChainMatch.first
                    logger.info(">>> CHAIN driver found via Spatial Pipeline: ${chainDriver.id}")
                    order.assignmentType = "CHAIN"
                    assignOrderToDriver(order, chainDriver)
                    return orderRepository.save(order)
                }
            }
        }

        val nearbyDrivers = getEnrichedNearbyDrivers(pickupLat, pickupLng, maxRadiusKm = 10.0, tariff = order.tariff)

        // 2. AUTO/CYCLE — Умный поиск по авто-фильтрам
        val autoDriver = findDriverByAutoFilter(order, nearbyDrivers)
        if (autoDriver != null) {
            logger.info(">>> FILTER driver selected via Memory Pipeline: ${autoDriver.id}")
            val matchedFilter = filterRepository.findAllByDriverId(autoDriver.id!!).firstOrNull { it.isActive }
            order.assignmentType = when {
                matchedFilter?.isCycle == true -> "CYCLE"
                matchedFilter?.fromType == "HOME" -> "HOME"
                else -> "AUTO"
            }
            assignOrderToDriver(order, autoDriver)
            return orderRepository.save(order)
        }

        // 3. SMART CANDIDATE (CHAIN / HOME на свободном водителе)
        val candidates = nearbyDrivers.filter { drv ->
            if (rejectedIds.contains(drv.id)) return@filter false
            if (drv.activityScore <= 0 || drv.photoControlRestricted) return@filter false

            val dist = GeometryUtils.calculateDistance(drv.latitude ?: 0.0, drv.longitude ?: 0.0, pickupLat, pickupLng)
            if (dist > drv.searchRadius) return@filter false

            when (drv.searchMode) {
                DriverSearchMode.CHAIN -> true
                DriverSearchMode.HOME -> drv.homeRidesLeft > 0 && drv.homeSectors.any { it.id == destSector?.id }
                else -> false
            }
        }

        if (candidates.isNotEmpty()) {
            val bestDriver = candidates.minByOrNull { drv ->
                estimateDrivingTimeSeconds(drv.latitude!!, drv.longitude!!, pickupLat, pickupLng)
            }!!
            logger.info(">>> SMART Selection via Redis GEO: Winner ${bestDriver.id} from ${candidates.size} candidates.")
            order.assignmentType = if (bestDriver.searchMode == DriverSearchMode.HOME) "HOME" else "CHAIN"
            assignOrderToDriver(order, bestDriver)
            return orderRepository.save(order)
        }

        // 4. ETHER — Если подходящих кандидатов больше не осталось, отправляем заказ в открытый Эфир
        logger.info(">>> No candidates left for order #${order.id}. Sending to Ether.")
        order.status = OrderStatus.REQUESTED
        order.offeredDriver = null
        order.offerExpiresAt = null
        order.assignmentType = "ETHER"
        val savedOrder = orderRepository.save(order)
        broadcastOrderChange(savedOrder, "ADD")
        return savedOrder
    }

    private fun assignOrderToDriver(order: TaxiOrder, driver: Driver) {
        val dist = calculateDistanceKm(
            driver.latitude ?: 0.0,
            driver.longitude ?: 0.0,
            order.originLat ?: 0.0,
            order.originLng ?: 0.0
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

    private fun estimateDrivingTimeSeconds(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val distKm = calculateDistanceKm(lat1, lon1, lat2, lon2)
        val realDistKm = distKm * 1.4
        val speedKmh = 30.0
        return (realDistKm / speedKmh) * 3600
    }

    private fun findDriverByAutoFilter(order: TaxiOrder, nearbyDrivers: List<Driver>): Driver? {
        val eligibleDrivers = nearbyDrivers.filter {
            !order.rejectedDriverIds.contains(it.id) && it.activityScore > 0 && !it.photoControlRestricted
        }
        val driverIds = eligibleDrivers.mapNotNull { it.id }
        if (driverIds.isEmpty()) return null

        val driversById = eligibleDrivers.associateBy { it.id }
        val activeFilters = filterRepository.findAllByDriverIdInAndIsActiveTrue(driverIds)
            .filter { it.isAuto || it.isCycle }

        val matchedFilter = activeFilters.firstOrNull { filter ->
            val driver = driversById[filter.driver.id] ?: return@firstOrNull false
            matchesFilter(order, filter, driver)
        }

        return matchedFilter?.let { driversById[it.driver.id] ?: it.driver }
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
            logger.info("Водій ${driver.id} відхилив пропозицію ${order.id}. Шукаємо наступного водія...")

            redisTemplate.opsForZSet().remove("orders:expired_offers", order.id.toString())

            driverActivityService.updateScore(driver, -50, "Відмова від запропонованого замовлення #${order.id}")

            order.rejectedDriverIds.add(driver.id!!)
            order.offeredDriver = null
            order.offerExpiresAt = null

            if (order.scheduledAt != null) {
                order.status = OrderStatus.SCHEDULED
                val saved = orderRepository.save(order)
                broadcastOrderChange(saved, "ADD")
            } else {
                dispatchOrderToNextCandidate(order)
            }
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
            val order = orderRepository.findByIdWithLock(orderId).orElse(null) ?: continue

            if (order.status == OrderStatus.OFFERING) {
                logger.info("Час пропозиції замовлення ${order.id} вичерпано (Redis ZSET). Штраф і пошук наступного водія.")

                order.offeredDriver?.let {
                    driverActivityService.updateScore(it, -50, "Пропущено замовлення #${order.id}")
                    order.rejectedDriverIds.add(it.id!!)
                }

                order.offeredDriver = null
                order.offerExpiresAt = null

                if (order.scheduledAt != null) {
                    order.status = OrderStatus.REQUESTED
                    val saved = orderRepository.save(order)
                    broadcastOrderChange(saved, "ADD")
                } else {
                    dispatchOrderToNextCandidate(order)
                }
            }
        }
    }

    fun broadcastOrderChange(order: TaxiOrder, action: String) {
        invalidateEtherCache()

        val orderUuidStr = order.uuid.toString()
        val fullOrderDto = TaxiOrderDto(order)

        val adminAndClientMessage = OrderSocketMessage(action, orderUuidStr, if (action == "REMOVE") null else fullOrderDto)

        sendSocketAfterCommit("/topic/admin/orders", adminAndClientMessage)
        sendSocketAfterCommit("/topic/clients/${order.client.id}/orders", adminAndClientMessage)

        val isAvailableInEther = order.status == OrderStatus.REQUESTED ||
            (order.status == OrderStatus.SCHEDULED && order.driver == null && order.offeredDriver == null)

        if (isAvailableInEther && action != "REMOVE") {
            // ЗАЩИТА ПЕРСОНАЛЬНЫХ ДАННЫХ: В публичном эфире скрываем номер телефона клиента (пустая строка для non-null String)
            val publicEtherDto = fullOrderDto.copy(
                client = fullOrderDto.client.copy(phoneNumber = "")
            )
            val etherMessage = OrderSocketMessage(action, orderUuidStr, publicEtherDto)
            sendSocketAfterCommit("/topic/drivers/ether", etherMessage)
        } else {
            sendSocketAfterCommit("/topic/drivers/ether", OrderSocketMessage("REMOVE", orderUuidStr, null))

            val targetDriver = order.driver ?: order.offeredDriver
            targetDriver?.id?.let { driverId ->
                sendSocketAfterCommit("/topic/drivers/$driverId/orders", adminAndClientMessage)
            }
        }
    }

    @Transactional(readOnly = true)
    fun getDriverHeatmap(): List<HeatmapZoneDto> {
        val activeOrders = getCachedAvailableEtherOrders().filter { it.status == OrderStatus.REQUESTED }
        if (activeOrders.isEmpty()) return emptyList()

        val R = 0.0112
        val VERT_STEP = 1.5 * R
        val HORIZ_BASE = Math.sqrt(3.0) * R

        val grouped = activeOrders.groupBy { order ->
            val lat = order.originLat ?: 0.0
            val lng = order.originLng ?: 0.0
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

    @Transactional(readOnly = true)
    fun getFilteredOrdersForDriver(driver: Driver): List<TaxiOrderDto> {
        val meta = redisTemplate.opsForHash<String, Any>().get("drivers:meta", driver.id.toString()) as? Map<*, *>
        val isOnlineRedis = (meta?.get("isOnline") as? String)?.toBoolean()
            ?: (meta?.get("isOnline") as? Boolean)
            ?: false

        val isOnline = driver.isOnline || isOnlineRedis
        if (!isOnline) {
            return emptyList()
        }

        if (driver.activityScore <= 0) {
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

        val allOrders = getCachedAvailableEtherOrders()
        if (allOrders.isEmpty()) return emptyList()

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
            .filter { it.isActive && it.isEther }

        val resultOrders = if (activeFilters.isEmpty()) {
            availableOrdersByTariff
        } else {
            availableOrdersByTariff.filter { order ->
                activeFilters.any { filter -> matchesFilter(order, filter, driver) }
            }
        }

        return resultOrders.map { order ->
            val dto = TaxiOrderDto(order)
            dto.copy(client = dto.client.copy(phoneNumber = ""))
        }
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
                val originSectorId = order.originSector?.id
                val isInStartSector = if (originSectorId != null) {
                    originSectorId in filter.fromSectors
                } else {
                    val startSectors = sectorService.getCachedSectorEntities().filter { it.id in filter.fromSectors }
                    startSectors.any { sector ->
                        GeometryUtils.isPointInPolygon(order.originLat ?: 0.0, order.originLng ?: 0.0, sector.points)
                    }
                }
                if (!isInStartSector) return false
            }
        }

        if (filter.toSectors.isNotEmpty()) {
            val destSectorId = order.destinationSector?.id
            val isInEndSector = if (destSectorId != null) {
                destSectorId in filter.toSectors
            } else {
                val endSectors = sectorService.getCachedSectorEntities().filter { it.id in filter.toSectors }
                endSectors.any { sector ->
                    GeometryUtils.isPointInPolygon(order.destLat ?: 0.0, order.destLng ?: 0.0, sector.points)
                }
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
            val priceSuburbs = filter.complexPriceKmSuburbs ?: priceCity

            val isOutOfCity = order.destinationSector?.isCity == false || order.originSector?.isCity == false
            val ratePerKm = if (isOutOfCity && priceSuburbs > 0.0) priceSuburbs else priceCity

            val requiredPrice = if (totalKm <= kmInMin) {
                minPrice
            } else {
                minPrice + (totalKm - kmInMin) * ratePerKm
            }
            if (order.price < requiredPrice) return false
        }
        return true
    }

    @Transactional(readOnly = true)
    fun findActiveOrderByDriver(driver: Driver): TaxiOrderDto? {
        val driverId = driver.id ?: return null

        val activeOrder = orderRepository.findActiveOrScheduledOrdersByDriverId(driverId).firstOrNull()
        if (activeOrder != null) {
            return TaxiOrderDto(activeOrder)
        }

        val offeredOrder = orderRepository.findOfferingOrdersByDriverId(driverId).firstOrNull()
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

    @Transactional(readOnly = true)
    fun findHistoryByDriver(driver: Driver): List<TaxiOrderDto> {
        val driverId = driver.id ?: return emptyList()

        val orders = orderRepository.findDriverHistoryOrders(driverId, PageRequest.of(0, 100))
        if (orders.isEmpty()) return emptyList()

        val orderIds = orders.mapNotNull { it.id }

        val activityMap = activityHistoryRepository.findAllByDriverIdAndOrderIdIn(driverId, orderIds)
            .filter { it.orderId != null }
            .associateBy { it.orderId!! }

        return orders.map { order ->
            val realPointsChange = activityMap[order.id]?.pointsChange ?: 0
            TaxiOrderDto(order).copy(activityBonus = realPointsChange)
        }
    }

    @Transactional(readOnly = true)
    fun getOrderById(id: Long): TaxiOrderDto {
        val order = orderRepository.findById(id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено") }
        return TaxiOrderDto(order)
    }

    @Transactional(readOnly = true)
    fun getOrderEntityById(id: Long): TaxiOrder {
        return orderRepository.findById(id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено") }
    }

    @Transactional(readOnly = true)
    fun getClientHistory(client: Client, page: Int = 0, size: Int = 30): List<TaxiOrderDto> {
        val pageable = PageRequest.of(page, size)
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
            } else {
                order.evosCancelPending = true
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
                liqPayService.voidFunds(paymentOrderId)
            } else {
                val penaltyAmount = order.tariff.basePrice
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

        val targetDriver = order.driver ?: order.offeredDriver
        if (targetDriver != null && (
                order.status == OrderStatus.OFFERING ||
                order.status == OrderStatus.ACCEPTED ||
                order.status == OrderStatus.DRIVER_ARRIVED ||
                order.status == OrderStatus.IN_PROGRESS ||
                order.status == OrderStatus.ARRIVED_AT_WAYPOINT
            )
        ) {
            notificationService.sendOrderCancelToDriver(targetDriver, order)
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

        if (order.paymentMethod == "CARD" && order.authorizedAmount > 0.0) {
            val paymentOrderId = "ride_${order.id}"
            liqPayService.voidFunds(paymentOrderId)
            order.authorizedAmount = 0.0
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
            val cancelledInEvos = evoSService.cancelOrderInEvoS(evosUid)
            if (cancelledInEvos) {
                order.isSentToEvos = false
                order.evosCancelPending = false
                order.evosOrderUid = null
            } else {
                order.evosCancelPending = true
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
        }
        val saved = orderRepository.save(order)

        if (saved.status != OrderStatus.SCHEDULED) {
            redisTemplate.opsForHash<String, Any>().put("orders:active_drivers", driver.id.toString(), "${saved.id}|${saved.uuid}")
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
                        redisTemplate.delete(lastFinishedKey)
                        return TaxiOrderDto(finishedOrder)
                    }
                    if (finishedOrder.status == OrderStatus.COMPLETED) {
                        return TaxiOrderDto(finishedOrder)
                    }
                }
            }
            redisTemplate.delete(lastFinishedKey)
        }

        return null
    }

    fun dismissCompletedOrder(client: Client, orderId: Long) {
        redisTemplate.delete("client:last_completed_order:${client.id}")
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

    fun fetchRouteFromOsrm(request: RouteRequestDto): RouteResponseDto? {
        val allPoints = mutableListOf<RoutePointDto>()
        allPoints.add(RoutePointDto(request.originLat, request.originLng))
        request.waypoints?.forEach { wp ->
            if (wp.lat != 0.0 || wp.lng != 0.0) {
                allPoints.add(wp)
            }
        }
        allPoints.add(RoutePointDto(request.destLat, request.destLng))

        val isValid = allPoints.all {
            it.lat in -90.0..90.0 && it.lng in -180.0..180.0 && (it.lat != 0.0 || it.lng != 0.0)
        }
        if (!isValid) return null

        val cacheKey = "osrm:route:v1:" + allPoints.joinToString(";") {
            String.format(java.util.Locale.US, "%.5f,%.5f", it.lat, it.lng)
        }

        try {
            val cachedStr = redisTemplate.opsForValue().get(cacheKey)?.toString()
            if (!cachedStr.isNullOrBlank()) {
                val parts = cachedStr.split("|||", limit = 3)
                if (parts.size == 3) {
                    val dist = parts[0].toIntOrNull()
                    val dur = parts[1].toIntOrNull()
                    val poly = parts[2]
                    if (dist != null && dur != null && poly.isNotBlank()) {
                        return RouteResponseDto(
                            polyline = poly,
                            distanceMeters = dist,
                            durationSeconds = dur
                        )
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn(">>> [OSRM Cache] Помилка читання Redis: ${e.message}")
        }

        return try {
            val coordsQuery = allPoints.joinToString(";") { "${it.lng},${it.lat}" }
            val osrmUrl = "https://router.project-osrm.org/route/v1/driving/$coordsQuery?overview=full&geometries=polyline"

            val connection = java.net.URL(osrmUrl).openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 2000
            connection.readTimeout = 2000
            connection.setRequestProperty("User-Agent", "UnitTaxiServer/1.0")

            if (connection.responseCode == 200) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val json = com.google.gson.JsonParser.parseString(responseText).asJsonObject
                val routes = json.getAsJsonArray("routes")
                if (routes != null && routes.size() > 0) {
                    val routeObj = routes[0].asJsonObject
                    val polyline = routeObj.get("geometry")?.takeIf { !it.isJsonNull }?.asString
                    val distanceMeters = routeObj.get("distance")?.takeIf { !it.isJsonNull }?.asDouble?.let { kotlin.math.round(it).toInt() } ?: 0
                    val durationSeconds = routeObj.get("duration")?.takeIf { !it.isJsonNull }?.asDouble?.let { kotlin.math.round(it).toInt() } ?: 0

                    if (!polyline.isNullOrBlank()) {
                        val result = RouteResponseDto(
                            polyline = polyline,
                            distanceMeters = max(1, distanceMeters),
                            durationSeconds = max(1, durationSeconds)
                        )
                        try {
                            val valueToCache = "${result.distanceMeters}|||${result.durationSeconds}|||${result.polyline}"
                            redisTemplate.opsForValue().set(cacheKey, valueToCache, 10, java.util.concurrent.TimeUnit.MINUTES)
                        } catch (_: Exception) {}
                        result
                    } else null
                } else null
            } else null
        } catch (e: Exception) {
            logger.warn(">>> [OSRM Router] Таймаут або помилка OSRM: ${e.message}")
            null
        }
    }

    fun fetchDirectionsPolyline(startLat: Double, startLng: Double, endLat: Double, endLng: Double): String? {
        return fetchRouteFromOsrm(
            RouteRequestDto(
                originLat = startLat,
                originLng = startLng,
                destLat = endLat,
                destLng = endLng
            )
        )?.polyline
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

        order.isDriverConfirmed = true
        order.status = OrderStatus.ACCEPTED

        val saved = orderRepository.save(order)
        redisTemplate.opsForHash<String, Any>().put("orders:active_drivers", driver.id.toString(), "${saved.id}|${saved.uuid}")

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
            } else {
                val isCaptureSuccess = liqPayService.captureFunds(paymentOrderId, order.authorizedAmount)

                if (!isCaptureSuccess) {
                    order.paymentMethod = "CASH"
                    orderRepository.save(order)
                    broadcastOrderChange(order, "UPDATE")
                    throw ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "Помилка списання базової суми холда. Переведено на ГОТІВКУ.")
                }
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
                } else {
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

        if (order.assignmentType == "HOME" || driver.searchMode == DriverSearchMode.HOME) {
            val now = LocalDateTime.now()
            val lastUsage = driver.lastHomeUsageDate
            if (lastUsage == null || lastUsage.toLocalDate() != now.toLocalDate()) {
                driver.homeRidesLeft = 2
            }
            driver.homeRidesLeft = (driver.homeRidesLeft - 1).coerceAtLeast(0)
            driver.lastHomeUsageDate = now
            if (driver.homeRidesLeft <= 0 && driver.searchMode == DriverSearchMode.HOME) {
                driver.searchMode = DriverSearchMode.MANUAL
            }
        }

        driverActivityService.processOrderCompletion(driver, order)
        order.client.tripsCount += 1
        order.client.totalCompletedOrders += 1
        clientRepository.save(order.client)

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
                if (order.scheduledAt!!.isBefore(now.plusMinutes(30)) && order.status == OrderStatus.SCHEDULED) {
                    order.status = OrderStatus.ACCEPTED
                    orderRepository.save(order)
                    broadcastOrderChange(order, "UPDATE")
                }
                continue
            }

            if (order.scheduledAt!!.isBefore(now.minusHours(1))) {
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
                    order.confirmationRequestedAt = LocalDateTime.now()
                    orderRepository.save(order)
                    notificationService.sendConfirmationRequest(order.driver!!, order)
                } else {
                    if (now.isAfter(order.confirmationRequestedAt!!.plusMinutes(1))) {
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
                val scheduledNearby = getEnrichedNearbyDrivers(order.originLat ?: 0.0, order.originLng ?: 0.0, maxRadiusKm = 10.0, tariff = order.tariff)

                val bestDriver = scheduledNearby.filter { drv ->
                    !order.rejectedDriverIds.contains(drv.id) &&
                    drv.activityScore > 0 &&
                    !drv.photoControlRestricted &&
                    drv.searchMode != DriverSearchMode.OFFLINE
                }.minByOrNull { drv ->
                    estimateDrivingTimeSeconds(drv.latitude!!, drv.longitude!!, order.originLat ?: 0.0, order.originLng ?: 0.0)
                }

                if (bestDriver != null) {
                    order.status = OrderStatus.OFFERING
                    order.offeredDriver = bestDriver
                    val expiresAt = LocalDateTime.now().plusSeconds(20)
                    order.offerExpiresAt = expiresAt

                    val epochSecond = expiresAt.toEpochSecond(java.time.ZoneOffset.UTC)
                    redisTemplate.opsForZSet().add("orders:expired_offers", order.id.toString(), epochSecond.toDouble())

                    val savedOffer = orderRepository.save(order)
                    notificationService.sendOrderOffer(bestDriver, savedOffer)
                    broadcastOrderChange(savedOffer, "UPDATE")
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
        if (pickupLat == 0.0 && pickupLng == 0.0) return emptyList()

        val circle = org.springframework.data.geo.Circle(
            org.springframework.data.geo.Point(pickupLng, pickupLat),
            org.springframework.data.geo.Distance(maxRadiusKm, org.springframework.data.geo.Metrics.KILOMETERS)
        )
        val args = org.springframework.data.redis.connection.RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
            .includeCoordinates().sortAscending()

        val geoResults = try {
            redisTemplate.opsForGeo().radius("drivers:geo", circle, args)
        } catch (e: Exception) {
            logger.warn(">>> [OrderMatching] Помилка читання drivers:geo з Redis: ${e.message}")
            null
        }

        val driversWithActiveOffers = orderRepository.findDriverIdsWithActiveOffers()
        val activeOrderDriverIds = orderRepository.findDriverIdsWithActiveOrders()

        val geoContent = geoResults?.content ?: emptyList()
        val driverIds = geoContent.mapNotNull { "${it.content.name}".toLongOrNull() }

        if (driverIds.isEmpty()) {
            val latDelta = maxRadiusKm / 111.0
            val lngDelta = maxRadiusKm / (111.0 * max(0.2, cos(Math.toRadians(pickupLat))))

            val dbOnlineDrivers = orderRepository.findOnlineDriversInBounds(
                minLat = pickupLat - latDelta,
                maxLat = pickupLat + latDelta,
                minLng = pickupLng - lngDelta,
                maxLng = pickupLng + lngDelta
            ).filter { drv ->
                drv.latitude != null && drv.longitude != null &&
                !driversWithActiveOffers.contains(drv.id) &&
                !activeOrderDriverIds.contains(drv.id) &&
                GeometryUtils.calculateDistance(drv.latitude!!, drv.longitude!!, pickupLat, pickupLng) <= maxRadiusKm
            }

            if (dbOnlineDrivers.isNotEmpty()) {
                for (drv in dbOnlineDrivers) {
                    val drvIdStr = drv.id.toString()
                    redisTemplate.opsForGeo().add("drivers:geo", org.springframework.data.geo.Point(drv.longitude!!, drv.latitude!!), drvIdStr)
                    val restoredMeta = mapOf(
                        "fullName" to (drv.fullName ?: "Водій"),
                        "carModel" to (drv.car?.model ?: "Не вказано"),
                        "carColor" to (drv.car?.color ?: ""),
                        "status" to drv.searchMode.name,
                        "isOnline" to "true",
                        "lat" to drv.latitude.toString(),
                        "lng" to drv.longitude.toString(),
                        "bearing" to (drv.bearing ?: 0f).toString(),
                        "updatedAt" to System.currentTimeMillis().toString()
                    )
                    redisTemplate.opsForHash<String, Any>().put("drivers:meta", drvIdStr, restoredMeta)
                }
                return dbOnlineDrivers.filter { drv -> isDriverTariffMatching(drv, tariff) }
            }
            return emptyList()
        }

        val driversFromDb = driverRepository.findAllById(driverIds).associateBy { it.id }

        val driverIdStrings = driverIds.map { it.toString() }
        val metaList = try {
            redisTemplate.opsForHash<String, Any>().multiGet("drivers:meta", driverIdStrings)
        } catch (_: Exception) {
            emptyList()
        }
        val metaByDriverId = driverIds.zip(metaList).toMap()

        return geoContent.mapNotNull { result ->
            val id = "${result.content.name}".toLongOrNull() ?: return@mapNotNull null

            if (driversWithActiveOffers.contains(id) || activeOrderDriverIds.contains(id)) {
                return@mapNotNull null
            }

            val driver = driversFromDb[id] ?: return@mapNotNull null
            if (driver.isBlocked) return@mapNotNull null

            if (!isDriverTariffMatching(driver, tariff)) {
                return@mapNotNull null
            }

            val meta = metaByDriverId[id] as? Map<*, *>
            if (meta != null) {
                val isOnlineRedis = (meta["isOnline"] as? String)?.toBoolean() ?: driver.isOnline
                if (!isOnlineRedis && !driver.isOnline) return@mapNotNull null

                driver.isOnline = true
                driver.latitude = result.content.point.y
                driver.longitude = result.content.point.x
                driver.bearing = (meta["bearing"] as? String)?.toFloatOrNull() ?: 0f
            } else if (!driver.isOnline) {
                return@mapNotNull null
            }
            driver
        }
    }

    private fun isDriverTariffMatching(driver: Driver, tariff: com.taxiapp.server.model.order.CarTariff?): Boolean {
        if (tariff == null) return true
        val driverTariffIds = if (driver.selectedTariffs.isNotEmpty()) {
            driver.selectedTariffs.mapNotNull { it.id }.toSet()
        } else {
            driver.allowedTariffs.mapNotNull { it.id }.toSet()
        }
        return driverTariffIds.isEmpty() ||
               driverTariffIds.contains(tariff.id) ||
               tariff.name.equals("Стандарт", ignoreCase = true) ||
               tariff.name.equals("Cтандарт", ignoreCase = true)
    }

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
        } else if (previousMethod == "CARD" && normalizedMethod != "CARD" && order.authorizedAmount > 0.0) {
            val paymentOrderId = "ride_${order.id}"
            liqPayService.voidFunds(paymentOrderId)
            order.authorizedAmount = 0.0
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

    @Transactional(readOnly = true)
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
            .sortedByDescending { it.idLong }
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