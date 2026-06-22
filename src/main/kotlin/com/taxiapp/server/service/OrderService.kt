package com.taxiapp.server.service

import com.taxiapp.server.dto.driver.HeatmapZoneDto
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import com.taxiapp.server.dto.order.CreateOrderRequestDto
import com.taxiapp.server.dto.order.TaxiOrderDto
import com.taxiapp.server.dto.order.OrderSocketMessage
import com.taxiapp.server.model.enums.OrderStatus
import com.taxiapp.server.model.order.TaxiOrder
import com.taxiapp.server.model.order.OrderStop
import com.taxiapp.server.model.user.Client
import com.taxiapp.server.model.user.Driver
import com.taxiapp.server.model.user.User
import com.taxiapp.server.model.filter.DriverFilter
import com.taxiapp.server.repository.*
import com.taxiapp.server.utils.GeometryUtils
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import com.taxiapp.server.dto.order.CalculatedTariffDto
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import com.taxiapp.server.service.NotificationService
import kotlin.math.max
import com.taxiapp.server.dto.tariff.CarTariffDto
import com.taxiapp.server.service.SettingsService

@Service
class OrderService(
    private val chatService: ChatService,
    private val notificationService: NotificationService,
    private val orderRepository: TaxiOrderRepository,
    private val tariffRepository: CarTariffRepository,
    private val promoService: PromoService,
    private val driverRepository: DriverRepository,
    private val promoCodeService: PromoCodeService,
    private val taxiServiceRepository: TaxiServiceRepository,
    private val filterRepository: DriverFilterRepository,
    private val sectorRepository: SectorRepository,
    private val messagingTemplate: SimpMessagingTemplate,
    private val driverActivityService: DriverActivityService,
    private val sectorService: SectorService,
    private val cancellationReasonRepository: CancellationReasonRepository,
    private val walletTransactionRepository: WalletTransactionRepository,
    private val appSettingRepository: AppSettingRepository,
    private val liqPayService: LiqPayService,
    private val activityHistoryRepository: DriverActivityHistoryRepository,
    private val redisTemplate: org.springframework.data.redis.core.RedisTemplate<String, String> // <-- ДОБАВЛЕНО
) {

    private val logger = LoggerFactory.getLogger(OrderService::class.java)

    private fun calculateExactTripPrice(
        tariffId: Long,
        polyline: String?,
        totalDistanceMeters: Int,
        serviceIds: List<Long>?,
        addedValue: Double,
        waypointsCount: Int = 0, // <--- НОВЫЙ ПАРАМЕТР: количество промежуточных точек
        isDebug: Boolean = false
    ): Double {
        val tariff = tariffRepository.findById(tariffId)
            .orElseThrow { RuntimeException("Tariff not found") }

        logger.info("[PRICE_CALC] === НАЧАЛО РАСЧЕТА ЦЕНЫ ЗАКАЗА ===")
        // В лог теперь добавляем инфу о цене за точку
        logger.info("[PRICE_CALC] ШАГ 1: Тариф ID=${tariff.id} (${tariff.name}) | BasePrice=${tariff.basePrice} | WaypointPrice=${tariff.extraWaypointPrice} | AddedValue=$addedValue")

        // 1. Считаем стоимость услуг
        var servicesCost = 0.0
        if (!serviceIds.isNullOrEmpty()) {
            val services = taxiServiceRepository.findAllById(serviceIds)
            servicesCost = services.sumOf { it.price }
            logger.info("[PRICE_CALC] ШАГ 2: Выбраны доп. услуги (${services.size} шт.) на сумму: $servicesCost")
        } else {
            logger.info("[PRICE_CALC] ШАГ 2: Доп. услуги не выбраны (Сумма: 0.0)")
        }

        // --- НОВОЕ: Считаем стоимость промежуточных точек ---
        val waypointsCost = waypointsCount * tariff.extraWaypointPrice
        logger.info("[PRICE_CALC] ШАГ 2.1: Промежуточные точки ($waypointsCount шт.) на сумму: $waypointsCost")

        // 2. Если маршрута нет (ручной ввод цены или ошибка), считаем базу + услуги + точки + надбавка
        if (polyline.isNullOrEmpty() || totalDistanceMeters == 0) {
            val totalNoDist = tariff.basePrice + servicesCost + addedValue + waypointsCost
            logger.info("[PRICE_CALC] ШАГ 3: Полилайн пуст. Возвращаем (База+Услуги+Точки+Надбавка) = $totalNoDist")
            logger.info("[PRICE_CALC] === КОНЕЦ РАСЧЕТА ===")
            return totalNoDist
        }

        // 3. Расчет по километражу (город/межгород)
        logger.info("[PRICE_CALC] ШАГ 3: Запрос разбивки дистанции у GeometryUtils. Передано: $totalDistanceMeters м.")
        val citySectors = sectorRepository.findAll().filter { it.isCity }
        val (metersCity, metersOutCity) = GeometryUtils.calculateRouteSplit(polyline, citySectors)

        val finalMetersCity = if (metersCity == 0.0 && metersOutCity == 0.0) totalDistanceMeters.toDouble() else metersCity
        val finalMetersOutCity = if (metersCity == 0.0 && metersOutCity == 0.0) 0.0 else metersOutCity

        val totalKmCity = finalMetersCity / 1000.0
        val totalKmOutCity = finalMetersOutCity / 1000.0

        // 4. Бесплатные километры (твоя логика про 3 км)
        val INCLUDED_KM = 3.0 
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
        
        // К сумме теперь добавляем waypointsCost
        var finalPrice = tariff.basePrice + routePrice + servicesCost + addedValue + waypointsCost
        
        logger.info("[PRICE_CALC] Сборка: База(${tariff.basePrice}) + КМ($routePrice) + Услуги($servicesCost) + Точки($waypointsCost) + Надбавка($addedValue) = $finalPrice")

        finalPrice = ceil(finalPrice)
        val result = max(finalPrice, tariff.basePrice)
        
        logger.info("[PRICE_CALC] ИТОГО: $result")
        logger.info("[PRICE_CALC] === КОНЕЦ РАСЧЕТА ===")
        
        return result
    }

    fun calculatePricesForRoute(polyline: String, totalMeters: Int, waypointsCount: Int = 0, client: Client? = null): List<CarTariffDto> {
        val tariffs = tariffRepository.findAll().filter { it.isActive }
        return tariffs.map { tariff ->
            
            // 1. Рассчитываем чистую цену по тарифу
            var price = calculateExactTripPrice(
                tariffId = tariff.id, 
                polyline = polyline, 
                totalDistanceMeters = totalMeters, 
                serviceIds = emptyList(), 
                addedValue = 0.0, 
                waypointsCount = waypointsCount,
                isDebug = false
            )
            
            var oldPrice: Double? = null // 👈 Хранилище для полной стоимости

            // 2. Применяем логику скидок
            if (client != null) {
                var discountAmount = 0.0
                var promoCodeApplied = false

                // Проверяем активный промокод клиента
                val activePromoUsage = promoCodeService.findActiveUsage(client)
                if (activePromoUsage != null) {
                    val isExpired = activePromoUsage.expiresAt != null && LocalDateTime.now().isAfter(activePromoUsage.expiresAt)
                    if (!isExpired) {
                        val percent = activePromoUsage.promoCode.discountPercent
                        var calcDiscount = price * (percent / 100.0)
                        val maxAmount = activePromoUsage.promoCode.maxDiscountAmount
                        if (maxAmount != null && calcDiscount > maxAmount) {
                            calcDiscount = maxAmount
                        }
                        discountAmount = calcDiscount
                        promoCodeApplied = true
                    }
                }

                // Если промокод не применился, проверяем маркетинговые награды
                if (!promoCodeApplied) {
                    val activeReward = promoService.findActiveReward(client)
                    if (activeReward != null) {
                        val task = activeReward.promoTask
                        val percent = task.discountPercent
                        discountAmount = price * (percent / 100.0)
                        if (task.maxDiscountAmount != null && discountAmount > task.maxDiscountAmount!!) {
                            discountAmount = task.maxDiscountAmount!!
                        }
                    }
                }

                // 🎁 Если скидка сработала, фиксируем старую цену и вычитаем дисконт
                if (discountAmount > 0.0) {
                    oldPrice = price // 👈 Запоминаем исходную цену без скидки
                    price -= discountAmount
                    
                    // Ставим минимальный порог поездки в 1 грн вместо tariff.basePrice,
                    // чтобы скидки честно работали на коротких поездках!
                    if (price < 1.0) price = 1.0 
                }
            }
            
            CarTariffDto(
                id = tariff.id,
                name = tariff.name,
                basePrice = tariff.basePrice,
                pricePerKm = tariff.pricePerKm,
                pricePerKmOutCity = tariff.pricePerKmOutCity,
                extraWaypointPrice = tariff.extraWaypointPrice, 
                freeWaitingMinutes = tariff.freeWaitingMinutes,
                pricePerWaitingMinute = tariff.pricePerWaitingMinute,
                isActive = tariff.isActive,
                imageUrl = tariff.imageUrl,
                isBeta = tariff.isBeta,               
                isUnavailable = tariff.isUnavailable,
                calculatedPrice = price, 
                description = null,
                oldPrice = oldPrice // 👈 Передаем старую цену в обновленное поле DTO
            )
        }
    }

    @Transactional
    fun createOrder(client: Client, request: CreateOrderRequestDto): TaxiOrderDto {
        logger.info(">>> СОЗДАНИЕ ЗАКАЗА: From='${request.fromAddress}'")
        val tariff = tariffRepository.findById(request.tariffId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Тариф не знайдено") }

        if (!tariff.isActive) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Тариф недоступний")
        }

        // ==========================================
        // ПЕРЕВІРКА ЛІМІТУ АКТИВНИХ ЗАМОВЛЕНЬ (Максимум 3)
        // ==========================================
        val activeStatuses = listOf(
            OrderStatus.REQUESTED,
            OrderStatus.OFFERING,
            OrderStatus.ACCEPTED,
            OrderStatus.DRIVER_ARRIVED,
            OrderStatus.IN_PROGRESS,
            OrderStatus.SCHEDULED
        )
        
        val activeOrdersCount = orderRepository.countActiveOrdersByClient(client.id!!, activeStatuses)
        if (activeOrdersCount >= 3) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Ви не можете мати більше 3 активних замовлень одночасно")
        }
        // ==========================================

        // --- Валідація часу ---
        if (request.scheduledAt != null) {
            val now = LocalDateTime.now()
            
            // Мінімальний буфер часу для запланованого замовлення.
            // Оскільки сервер починає пошук водія за 30-35 хвилин до подачі, 
            // клієнт має створювати таке замовлення мінімум за 40 хвилин.
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

        // --- Розрахунок базової ціни ---
        val calculatedPrice = calculateExactTripPrice(
            tariffId = request.tariffId,
            polyline = request.googleRoutePolyline,
            totalDistanceMeters = request.distanceMeters ?: 0,
            serviceIds = request.serviceIds,
            addedValue = request.addedValue ?: 0.0,
            waypointsCount = waypointsCount,
            isDebug = true
        )

        // --- Логіка знижок (Promo) ---
        var discountAmount = 0.0
        var isPromoCodeUsedForThisOrder = false
        var promoCodeApplied = false

        val activePromoUsage = promoCodeService.findActiveUsage(client)
        if (activePromoUsage != null) {
            val isExpired = activePromoUsage.expiresAt != null && LocalDateTime.now().isAfter(activePromoUsage.expiresAt)
            if (!isExpired) {
                val percent = activePromoUsage.promoCode.discountPercent
                var calcDiscount = calculatedPrice * (percent / 100.0)
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
                discountAmount = calculatedPrice * (percent / 100.0)
                if (task.maxDiscountAmount != null && discountAmount > task.maxDiscountAmount!!) {
                    discountAmount = task.maxDiscountAmount!!
                }
                isPromoCodeUsedForThisOrder = false
            }
        }

        // ==========================================
        // 🛠️ НОВА ФІНАНСОВА МАТЕМАТИКА АГРЕГАТОРА:
        // ==========================================
        val fullPrice = calculatedPrice 

        // Вычисляем чистую долю клиента
        var clientPayAmount = fullPrice - discountAmount
        if (clientPayAmount < 1.0) { // 👈 ИСПРАВЛЕНО: Ставим порог 1.0 вместо tariff.basePrice, чтобы заказ на 2км создавался со скидкой!
            clientPayAmount = 1.0
        }
        
        // Фиксируем сумму скидки для компенсации водителю
        val actualDiscountApplied = fullPrice - clientPayAmount
        // ==========================================

        // --- Визначення секторів ---
        val destSector = if (request.destLat != null && request.destLng != null) {
            sectorRepository.findSectorByCoordinates(request.destLat, request.destLng)
        } else null

        val originSector = if (request.originLat != null && request.originLng != null) {
            sectorRepository.findSectorByCoordinates(request.originLat, request.originLng)
        } else null

        val initialStatus = if (request.scheduledAt != null) OrderStatus.SCHEDULED else OrderStatus.REQUESTED

        // --- Створення об'єкта замовлення ---
        val newOrder = TaxiOrder(
            client = client,
            fromAddress = request.fromAddress,
            toAddress = request.toAddress,
            status = initialStatus,
            createdAt = LocalDateTime.now(),
            tariff = tariff,
            price = fullPrice, // 👈 Зберігаємо ПОВНУ ціну для водія
            appliedDiscount = actualDiscountApplied, // 👈 Зберігаємо ЧЕСТНУ суму знижки для компенсації
            isPromoCodeUsed = isPromoCodeUsedForThisOrder,
            originLat = request.originLat ?: 0.0,
            originLng = request.originLng ?: 0.0,
            destLat = request.destLat ?: 0.0,
            destLng = request.destLng ?: 0.0,
            googleRoutePolyline = request.googleRoutePolyline,
            distanceMeters = request.distanceMeters ?: 0,
            durationSeconds = request.durationSeconds ?: 0,
            tariffName = tariff.name,
            comment = request.comment,
            paymentMethod = request.paymentMethod ?: "CASH",
            addedValue = request.addedValue ?: 0.0,
            destinationSector = destSector,
            originSector = originSector,
            scheduledAt = request.scheduledAt
        )

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

        // --- Збереження запланованого замовлення (До збереження ID) ---
        if (initialStatus == OrderStatus.SCHEDULED) {
            logger.info("Order scheduled for ${newOrder.scheduledAt}")
            val savedScheduled = orderRepository.save(newOrder)
            broadcastOrderChange(savedScheduled, "ADD")
            return TaxiOrderDto(savedScheduled)
        }

        // Зберігаємо замовлення в БД, щоб згенерувати ID для транзакцій LiqPay
        var savedOrder = orderRepository.save(newOrder)

        if (savedOrder.paymentMethod == "CARD") {
            val token = client.cardToken
            if (token.isNullOrEmpty()) {
                throw ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "У вас немає прив'язаної картки для оплати")
            }
            val paymentOrderId = "ride_${savedOrder.id}"
            
            // 🛡️ БЕЗПЕКА: Списуємо/резервуємо з картки клієнта ТІЛЬКИ його чисту суму зі знижкою!
            val isHoldSuccess = liqPayService.holdWithToken(
                orderId = paymentOrderId,
                amount = clientPayAmount, // 👈 Передаємо суму клієнта
                cardToken = token,
                description = "Резервування коштів за поїздку UNIT #${savedOrder.id}"
            )
            
            if (!isHoldSuccess) {
                throw ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "Недостатньо коштів на картці або помилка банку")
            }
            
            // Фіксуємо реальну суму заблокованого холда клієнта в БД
            savedOrder.authorizedAmount = clientPayAmount
            savedOrder = orderRepository.save(savedOrder)
        }

        // --- Збереження запланованого замовлення (Після обробки CARD, про всяк випадок) ---
        if (initialStatus == OrderStatus.SCHEDULED) {
            logger.info("Order scheduled for ${savedOrder.scheduledAt}")
            broadcastOrderChange(savedOrder, "ADD")
            return TaxiOrderDto(savedOrder)
        }

        // =====================================================================
        // 🚀 ЛОГІКА РОЗПОДІЛУ (WATERFALL): Ланцюг -> Авто/Цикл -> Ефір
        // =====================================================================
        val rejectedIds = if (savedOrder.rejectedDriverIds.isNotEmpty()) savedOrder.rejectedDriverIds.toList() else null

       // Рассчитываем порог активности водителя (не более 45 секунд с последнего пинга логов)
        val lastSeenThreshold = LocalDateTime.now().minusSeconds(45)

        // 1. ЛАНЦЮГ (Chain)
        val chainDriver = driverRepository.findBestChainDriver(
            pickupLat = savedOrder.originLat!!,
            pickupLng = savedOrder.originLng!!,
            rejectedDriverIds = rejectedIds,
            lastSeenThreshold = lastSeenThreshold // 👈 Передаем порог
        ).orElse(null)

        if (chainDriver != null) {
            logger.info(">>> CHAIN driver found: ${chainDriver.id}")
            assignOrderToDriver(savedOrder, chainDriver)
            savedOrder.assignmentType = "CHAIN"
            savedOrder = orderRepository.save(savedOrder)
        } else {
            // 2. AUTO/CYCLE - УМНЫЙ ПОИСК
            val matchedFilter = filterRepository.findAllByDriverId(savedOrder.driver?.id ?: 0L)
                .filter { it.isActive }.firstOrNull() 

            val autoDriver = findDriverByAutoFilter(savedOrder)

            if (autoDriver != null) {
                logger.info(">>> FILTER driver selected: ${autoDriver.id}")
                savedOrder.assignmentType = when {
                    matchedFilter?.isCycle == true -> "CYCLE"
                    matchedFilter?.fromType == "HOME" -> "HOME"
                    else -> "AUTO"
                }
                assignOrderToDriver(savedOrder, autoDriver)
            } else {
                // 3. ETHER / BEST CANDIDATE (Ефір або пошук найкращого)
                val candidates = driverRepository.findBestDriversCandidates(
                    savedOrder.originLat!!,
                    savedOrder.originLng!!,
                    destSector?.id,
                    rejectedIds,
                    lastSeenThreshold = lastSeenThreshold // 👈 Передаем порог
                )

                if (candidates.isNotEmpty()) {
                    val bestDriver = selectFastestDriver(candidates, savedOrder.originLat!!, savedOrder.originLng!!)
                    logger.info(">>> SMART Selection: Winner ${bestDriver.id} from ${candidates.size} candidates.")
                    assignOrderToDriver(savedOrder, bestDriver)
                    savedOrder = orderRepository.save(savedOrder)
                } else {
                    logger.info(">>> No drivers found. Sending to Ether.")
                    savedOrder.status = OrderStatus.REQUESTED
                    savedOrder.assignmentType = "ETHER"
                    savedOrder = orderRepository.save(savedOrder)
                    
                    broadcastOrderChange(savedOrder, "ADD")
                }
            }
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

        val expiresAt = LocalDateTime.now().plusSeconds(20) // Фиксируем время
        order.status = OrderStatus.OFFERING
        order.offeredDriver = driver
        order.offerExpiresAt = expiresAt
        
        // 🔥 ВНЕДРЯЕМ: Добавление в Redis Sorted Set (ZSET)
        // Score = timestamp в секундах, когда заказ сгорит.
        val epochSecond = expiresAt.toEpochSecond(java.time.ZoneOffset.UTC)
        redisTemplate.opsForZSet().add("orders:expired_offers", order.id.toString(), epochSecond.toDouble())

        notificationService.sendOrderOffer(driver, order)
        broadcastOrderChange(order, "UPDATE")
    }

    private fun selectFastestDriver(drivers: List<Driver>, targetLat: Double, targetLng: Double): Driver {
        // Простая эвристика: выбираем того, кто ближе, но с учетом "коэффициента извилистости"
        return drivers.minByOrNull { driver ->
            estimateDrivingTimeSeconds(driver.latitude!!, driver.longitude!!, targetLat, targetLng)
        } ?: drivers.first()
    }

    private fun estimateDrivingTimeSeconds(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val distKm = calculateDistanceKm(lat1, lon1, lat2, lon2)
        val realDistKm = distKm * 1.4 // Учитываем повороты
        val speedKmh = 30.0 // Средняя скорость в городе
        return (realDistKm / speedKmh) * 3600
    }


    
    private fun findDriverByAutoFilter(order: TaxiOrder): Driver? {
        val rejectedIds = if (order.rejectedDriverIds.isNotEmpty()) order.rejectedDriverIds.toList() else null

        // Викликаємо метод репозиторію, який ми створили раніше
        val filters = filterRepository.findMatchingAutoFilters(
            orderLat = order.originLat ?: 0.0,
            orderLng = order.originLng ?: 0.0,
            orderSectorId = order.originSector?.id,
            orderPrice = order.price,
            rejectedDriverIds = rejectedIds
        )

        // Якщо знайшли фільтри, беремо першого водія
        // (Можна додати сортування по дистанції, якщо репозиторій повертає кілька)
        return filters.firstOrNull()?.driver
    }

    private fun calculateDistanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371 // Радиус Земли
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
        val order = orderRepository.findById(orderId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }
        
        if (order.status == OrderStatus.OFFERING && order.offeredDriver?.id == driver.id) {
        logger.info("Водій ${driver.id} відхилив пропозицію ${order.id}")
        
        // 🔥 ВНЕДРЯЕМ: Очистка таймаута из Redis
        redisTemplate.opsForZSet().remove("orders:expired_offers", order.id.toString())
            
            // --- ЛОГИКА ШТРАФА ---
            // Если заказ был в статусе OFFERING и имел назначенного водителя, значит это был
            // либо АВТО, либо ЦЕПОЧКА, либо ПЕРСОНАЛЬНОЕ предложение.
            // За отказ - штраф.
            
            driverActivityService.updateScore(driver, -50, "Відмова від запропонованого замовлення #${order.id}")
            
            order.rejectedDriverIds.add(driver.id!!)
            order.status = OrderStatus.REQUESTED // Сбрасываем в Эфир
            order.offeredDriver = null
            order.offerExpiresAt = null
            
            val saved = orderRepository.save(order)
            broadcastOrderChange(saved, "ADD") // Уведомляем всех в Эфире
        }
    }

    @Scheduled(fixedRate = 2000) // 🔥 Оптимизировано: проверка каждые 2 сек через сверхбыстрый Redis
    @Transactional
    fun checkExpiredOffers() {
        val nowEpoch = java.time.Instant.now().epochSecond.toDouble()
        
        // Забираем из Redis только те ID заказов, у которых время предложения УЖЕ истекло (от 0 до текущего момента)
        val expiredIds = redisTemplate.opsForZSet().rangeByScore("orders:expired_offers", 0.0, nowEpoch) ?: emptySet()

        if (expiredIds.isEmpty()) return

        for (idStr in expiredIds) {
            val orderId = idStr.toLongOrNull() ?: continue
            val order = orderRepository.findById(orderId).orElse(null) ?: continue
            
            // Если заказ всё еще ждет этого водителя — наказываем и возвращаем в эфир
            if (order.status == OrderStatus.OFFERING) {
                logger.info("Час пропозиції замовлення ${order.id} вичерпано (Redis ZSET). Штраф і перехід в Ефір.")
                
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
            
            // Обязательно вычищаем обработанный ID из Redis Sorted Set
            redisTemplate.opsForZSet().remove("orders:expired_offers", idStr)
        }
    }
    
    // =================================================================================
    // 🧠 ОПТИМИЗИРОВАННЫЙ broadcastOrderChange ДЛЯ ЧИСТЫХ СОКЕТОВ
    // =================================================================================
    fun broadcastOrderChange(order: TaxiOrder, action: String) {
        val orderUuidStr = order.uuid.toString() // <-- ДОБАВИЛИ СТРОКОВЫЙ UUID ДЛЯ СОКЕТОВ
        val orderDto = TaxiOrderDto(order)
        
        val message = OrderSocketMessage(action, orderUuidStr, if (action == "REMOVE") null else orderDto)

        // 1. Диспетчерская (Админы) видят всё
        sendSocketAfterCommit("/topic/admin/orders", message)

        // 2. КЛИЕНТ (Пассажир) получает обновление своего заказа
        sendSocketAfterCommit("/topic/clients/${order.client.id}/orders", message)

        // 3. ВОДИТЕЛИ (Архитектура без циклов и перегрузки БД)
        if (order.status == OrderStatus.REQUESTED) {
            // Если заказ в общем эфире — пушим его в единый топик для всех свободных водителей
            sendSocketAfterCommit("/topic/drivers/ether", message)
        } else {
            // Если заказ перестает быть REQUESTED (его приняли, отменили диспетчером или скрыли)
            // отправляем команду REMOVE в общий эфир водителей, чтобы он мгновенно пропал с экранов остальных
            sendSocketAfterCommit("/topic/drivers/ether", OrderSocketMessage("REMOVE", orderUuidStr, null))
            
            // Отправляем точечное сокет-сообщение только тому водителю, кому он назначен или предложен
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
        if (!driver.isOnline) return emptyList()
        if (driver.activityScore <= 0) return emptyList()

        // --- ВИПРАВЛЕННЯ: Використовуємо новий метод, щоб приховати зайняті SCHEDULED ---
        val allOrders = orderRepository.findAllAvailableForEther()
        // -------------------------------------------------------------------------------

        val activeFilters = filterRepository.findAllByDriverId(driver.id!!)
            .filter { it.isActive }

        if (activeFilters.isEmpty()) {
            return allOrders.map { TaxiOrderDto(it) }
        }

        val filtered = allOrders.filter { order ->
            activeFilters.any { filter -> matchesFilter(order, filter, driver) }
        }

        return filtered.map { TaxiOrderDto(it) }
    }

    private fun matchesFilter(order: TaxiOrder, filter: DriverFilter, driver: Driver): Boolean {
        if (filter.fromType == "DISTANCE") {
            val distToOrder = GeometryUtils.calculateDistance(
                driver.latitude ?: 0.0, driver.longitude ?: 0.0,
                order.originLat ?: 0.0, order.originLng ?: 0.0
            )
            if (distToOrder > (filter.fromDistance ?: 30.0)) return false
        } else {
            if (filter.fromSectors.isNotEmpty()) {
                val startSectors = sectorRepository.findAllById(filter.fromSectors)
                val isInStartSector = startSectors.any { sector -> 
                    val sorted = sector.points.sortedBy { it.pointOrder }
                    GeometryUtils.isPointInPolygon(order.originLat ?: 0.0, order.originLng ?: 0.0, sorted)
                }
                if (!isInStartSector) return false
            }
        }

        if (filter.toSectors.isNotEmpty()) {
            val endSectors = sectorRepository.findAllById(filter.toSectors)
            val isInEndSector = endSectors.any { sector ->
                val sorted = sector.points.sortedBy { it.pointOrder }
                GeometryUtils.isPointInPolygon(order.destLat ?: 0.0, order.destLng ?: 0.0, sorted)
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
            OrderStatus.ARRIVED_AT_WAYPOINT // <--- ВАЖНО: Добавили это!
        )
        // Ищем заказ, где этот водитель назначен главным (driver_id)
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
        // 1. Извлекаем чистый список выполненных/отмененных заказов водителя
        val orders = orderRepository.findAllByDriverId(driver.id!!)
            .filter { it.status == OrderStatus.COMPLETED || it.status == OrderStatus.CANCELLED }
            .sortedByDescending { it.id }

        if (orders.isEmpty()) return emptyList()

        // 2. Собираем пачку ID заказов, у которых id не null
        val orderIds = orders.mapNotNull { it.id }

        // 3. СТРЕЕМИТЕЛЬНЫЙ ХИТ: Делаем ОДИН запрос к логам и превращаем результат в ассоциативную Map [orderId -> Лог]
        val activityMap = activityHistoryRepository.findAllByDriverIdAndOrderIdIn(driver.id!!, orderIds)
            .filter { it.orderId != null }
            .associateBy { it.orderId!! }

        // 4. Безопасно сопоставляем данные в памяти сервера со скоростью O(1)
        return orders.map { order ->
            val realPointsChange = activityMap[order.id]?.pointsChange ?: 0
            TaxiOrderDto(order).copy(activityBonus = realPointsChange)
        }
    }

    fun getOrderById(id: Long): TaxiOrderDto {
        val order = orderRepository.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено") }
        return TaxiOrderDto(order)
    }

    fun getClientHistory(client: Client): List<TaxiOrderDto> {
        return orderRepository.findAllByClientId(client.id!!).map { TaxiOrderDto(it) }
    }

    @Transactional
    // ОНОВЛЕНО: додано reasonText
    fun cancelOrder(user: User, orderId: Long, reasonText: String? = null): TaxiOrderDto {
        val order = orderRepository.findById(orderId).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }
    if (order.client.id != user.id) throw ResponseStatusException(HttpStatus.FORBIDDEN)

    // 🔥 ИСПРАВЛЕНО: Для HashOperations используется метод delete
    order.driver?.let { drv ->
        redisTemplate.opsForHash<String, String>().delete("orders:active_drivers", drv.id.toString())
    }

        // --- ЛОГИКА РАЗМОРОЗКИ ИЛИ СНЯТИЯ МИНИМАЛКИ ПРИ ОТМЕНЕ КЛИЕНТОМ ---
        if (order.paymentMethod == "CARD" && order.authorizedAmount > 0.0) {
            val paymentOrderId = "ride_${order.id}"
            val acceptedTime = order.acceptedAt
            val now = LocalDateTime.now()

            // Если водителя еще нет ИЛИ с момента принятия заказа прошло меньше 3-х минут
            if (acceptedTime == null || java.time.temporal.ChronoUnit.MINUTES.between(acceptedTime, now) < 3) {
                logger.info("Клиент отменил вовремя или машина не найдена. Полный возврат холда.")
                liqPayService.voidFunds(paymentOrderId)
            } else {
                // Прошло 3 минуты и более — удерживаем стоимость подачи (минималку из БД)
                val penaltyAmount = order.tariff.basePrice
                logger.info("Прошло больше 3 минут. Списываем стоимость подачи: $penaltyAmount UAH")
                
                liqPayService.captureFunds(paymentOrderId, penaltyAmount)

                // Выплачиваем эту подачу на баланс водителю за вычетом комиссии системы
                val commissionSetting = appSettingRepository.findById("driver_commission_percent").orElse(null)
                val commissionPercent = commissionSetting?.value?.toDoubleOrNull() ?: 10.0
                val commissionAmount = penaltyAmount * (commissionPercent / 100.0)
                val driverPayout = penaltyAmount - commissionAmount

                order.driver?.let { driver ->
                    driver.balance += driverPayout
                    driverRepository.save(driver)

                    // Фиксируем транзакцию компенсации в кошельке водителя
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
        }
        
        val assignedDriver = order.driver
if (assignedDriver != null && (
    order.status == OrderStatus.OFFERING ||          // Если карточка еще только висит на экране подбора
    order.status == OrderStatus.ACCEPTED ||          // Если водитель взял и едет на подачу
    order.status == OrderStatus.DRIVER_ARRIVED ||    // Если уже стоит на месте и ждет клиента
    order.status == OrderStatus.IN_PROGRESS ||       // Если уже едут по маршруту
    order.status == OrderStatus.ARRIVED_AT_WAYPOINT  // Если остановились на промежуточной точке
)) {
    // Вызываем наш новый точечный метод пуша
    notificationService.sendOrderCancelToDriver(assignedDriver, order)
}

        // НОВЕ: Зберігаємо причину скасування
        if (reasonText != null) {
            order.cancellationReason = reasonText
        }

        order.status = OrderStatus.CANCELLED
        val saved = orderRepository.save(order)
        broadcastOrderChange(saved, "ADD")

        // (інший код методу залишається без змін)
        
        chatService.clearChatForOrder(orderId) 
        broadcastOrderChange(saved, "REMOVE")
        return TaxiOrderDto(saved)
    }

   @Transactional
fun driverCancelOrder(driver: Driver, orderId: Long, reasonId: Long?): TaxiOrderDto {
    // 🔥 ИСПРАВЛЕНО: Для HashOperations используется метод delete
    redisTemplate.opsForHash<String, String>().delete("orders:active_drivers", driver.id.toString())
        val order = orderRepository.findById(orderId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено") }

        if (order.driver?.id != driver.id) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Це не ваше замовлення")
        
        if (order.status == OrderStatus.COMPLETED || order.status == OrderStatus.CANCELLED) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Замовлення вже закрите")
        }

        // --- ЛОГІКА ПРИЧИН ОТМЕНЫ ---
        var penalty = 50 
        var reasonText = "Не вказано"

        if (reasonId != null) {
            val reasonObj = cancellationReasonRepository.findById(reasonId).orElse(null)
            if (reasonObj != null) {
                penalty = reasonObj.penaltyScore
                reasonText = reasonObj.reasonText
            }
        }

        // --- ВИПРАВЛЕННЯ 2: ПОВЕРНЕННЯ В ЕФІР ДЛЯ ЗАПЛАНОВАНИХ ---
        if (order.status == OrderStatus.SCHEDULED) {
            logger.info("Driver ${driver.id} rejected SCHEDULED order #${order.id}. Returning to Ether.")
            
            // 👈 Проверяем, подтвердил ли уже его водитель (-50 или -30)
            val adaptivePenalty = if (order.isDriverConfirmed == true) 50 else 30
            val adaptiveText = if (order.isDriverConfirmed == true) "$reasonText (Скасовано підтверджене заплановане)" else "$reasonText (Скасовано заплановане)"
            
            driverActivityService.processOrderCancellation(driver, orderId, adaptivePenalty, adaptiveText)
            
            // Очищаємо водія, але не скасовуємо замовлення повністю
            order.rejectedDriverIds.add(driver.id!!)
            order.driver = null
            order.isDriverConfirmed = false
            order.confirmationRequestedAt = null
            order.offeredDriver = null
            
            // Замовлення залишається SCHEDULED, але тепер без водія -> broadcast надішле його всім
            val saved = orderRepository.save(order)
            broadcastOrderChange(saved, "ADD") 
            
            return TaxiOrderDto(saved)
        }
        // ---------------------------------------------------------

        // Стандартне скасування для активних замовлень
        order.cancellationReason = reasonText
        driverActivityService.processOrderCancellation(driver, orderId, penalty, reasonText)

        order.status = OrderStatus.CANCELLED
        val saved = orderRepository.save(order)

        // <--- ОЧИСТКА ЧАТА --->
        chatService.clearChatForOrder(orderId)

        broadcastOrderChange(saved, "REMOVE")
        return TaxiOrderDto(saved)
    }
    
    @Transactional
    fun acceptOrder(driver: Driver, orderId: Long): TaxiOrderDto {
        if (!driver.isOnline) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Переключіть режим на Онлайн")
        }
        if (driver.activityScore <= 0) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Низька активність.")

        val order = orderRepository.findById(orderId).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }
        
        // Логика для обычных предложений (OFFERING)
        if (order.status == OrderStatus.OFFERING) {
            if (order.offeredDriver?.id != driver.id) throw ResponseStatusException(HttpStatus.CONFLICT, "Зайнято")
            
            // Очистка таймаута из Redis
            redisTemplate.opsForZSet().remove("orders:expired_offers", order.id.toString())
        } 
        // Логика для запланированных (SCHEDULED)
        else if (order.status == OrderStatus.SCHEDULED) {
            // Если у заказа уже есть водитель
            if (order.driver != null) throw ResponseStatusException(HttpStatus.CONFLICT, "Вже має водія")
        }
        else if (order.status != OrderStatus.REQUESTED) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Замовлення вже зайняте")
        }
        
        // НАЗНАЧАЕМ ВОДИТЕЛЯ
        order.driver = driver
        order.offeredDriver = null
        order.acceptedAt = LocalDateTime.now()
        
        // Если это запланированный заказ -> статус НЕ меняем (остается SCHEDULED)
        // Если это обычный заказ -> ставим ACCEPTED
        if (order.status != OrderStatus.SCHEDULED) {
            order.status = OrderStatus.ACCEPTED
        } else {
            logger.info("Driver ${driver.id} reserved scheduled order ${order.id}")
        }
        
        // Списываем поездки "Домой" (если надо) ...
        
        val saved = orderRepository.save(order)

        // 🔥 ВНЕДРЕНО: Якщо замовлення активне (не SCHEDULED), додаємо маппинг водія до Redis Hash для трекингу
        if (saved.status != OrderStatus.SCHEDULED) {
            redisTemplate.opsForHash<String, String>().put("orders:active_drivers", driver.id.toString(), saved.uuid.toString())
        }

        broadcastOrderChange(saved, "ADD") // Обновляем диспетчера и водителя

        // Отправка Push-уведомления клиенту при принятии заказа водителем
        if (saved.status == OrderStatus.ACCEPTED) {
            notificationService.sendOrderStatusToClient(
                token = saved.client.fcmToken,
                orderId = saved.id!!,
                status = saved.status.name, // "ACCEPTED"
                title = "Водій знайден",
                body = "Водій прийняв ваше замовлення та прямує до вас."
            )
        }
        
        return TaxiOrderDto(saved)
    }

    @Transactional
    fun driverArrived(driver: Driver, orderId: Long): TaxiOrderDto {
        val order = orderRepository.findById(orderId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено") }

        if (order.driver?.id != driver.id) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Це не ваше замовлення")
        if (order.status != OrderStatus.ACCEPTED) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Невірний статус")

        order.status = OrderStatus.DRIVER_ARRIVED
        order.arrivedAt = LocalDateTime.now() 
        
        val savedOrder = orderRepository.save(order)
        broadcastOrderChange(savedOrder, "UPDATE") // <-- ДОБАВЛЕНО: Мгновенное оповещение

        notificationService.sendOrderStatusToClient(
            token = savedOrder.client.fcmToken,
            orderId = savedOrder.id!!,
            status = savedOrder.status.name, // "DRIVER_ARRIVED"
            title = "Водій на місці",
            body = "Ваше таксі прибуло, виходьте."
        )
        
        return TaxiOrderDto(savedOrder)
    }

    @Transactional
    fun startTrip(driver: Driver, orderId: Long): TaxiOrderDto {
        val order = orderRepository.findById(orderId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено") }

        if (order.driver?.id != driver.id) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Це не ваше замовлення")
        if (order.status != OrderStatus.ACCEPTED && order.status != OrderStatus.DRIVER_ARRIVED) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Не можна почати поїздку")
        }

        order.status = OrderStatus.IN_PROGRESS
        order.startedAt = LocalDateTime.now()

        // --- ЛОГИКА РАСЧЕТА ПЛАТНОГО ОЖИДАНИЯ ---
        if (order.arrivedAt != null) {
            // 💡 Если водитель приехал раньше запланированного времени, бесплатное ожидание начнется строго в scheduledAt
            val waitingStart = if (order.scheduledAt != null && order.arrivedAt!!.isBefore(order.scheduledAt)) {
                order.scheduledAt!!
            } else {
                order.arrivedAt!!
            }
            
            // maxOf(0, ...) гарантирует, что если клиент вышел еще ДО времени подачи, мы не улетим в минус
            val minutesWaited = maxOf(0, java.time.temporal.ChronoUnit.MINUTES.between(waitingStart, order.startedAt).toInt())
            val freeMinutes = order.tariff.freeWaitingMinutes
            
            if (minutesWaited > freeMinutes) {
                val paidMinutes = minutesWaited - freeMinutes
                val extraCost = paidMinutes * order.tariff.pricePerWaitingMinute
                
                order.waitingPrice = extraCost
                order.price += extraCost // Добавляем к общей стоимости заказа
                
                logger.info("Paid waiting applied for Order ${order.id}: $paidMinutes mins. Extra cost: $extraCost")
            }
        }
        // ---------------------------------------

        val savedOrder = orderRepository.save(order)
        broadcastOrderChange(savedOrder, "UPDATE") // <-- ДОБАВЛЕНО: Рассылка новой цены и статуса

        notificationService.sendOrderStatusToClient(
            token = savedOrder.client.fcmToken,
            orderId = savedOrder.id!!,
            status = savedOrder.status.name, // "IN_PROGRESS"
            title = "В дорозі",
            body = "Поїздка розпочалася. Приємної дороги!"
        )
        
        return TaxiOrderDto(savedOrder)
    }

    @Transactional
    fun confirmScheduledOrder(driver: Driver, orderId: Long): TaxiOrderDto {
        val order = orderRepository.findById(orderId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }

        if (order.driver?.id != driver.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Це не ваше замовлення")
        }
        if (order.status != OrderStatus.SCHEDULED) {
            // Якщо замовлення вже ACCEPTED, просто повертаємо ОК, нічого не міняємо
            return TaxiOrderDto(order)
        }

        logger.info("Driver ${driver.id} CONFIRMED scheduled order #${order.id}")
        
        // 1. Ставимо мітку підтвердження
        order.isDriverConfirmed = true
        
        // 2. Переводимо в статус ACCEPTED (Активне)
        // Тепер воно поводиться як звичайне замовлення: можна натиснути "На місці"
        order.status = OrderStatus.ACCEPTED
        
        val saved = orderRepository.save(order)

        // 🔥 ВНЕДРЕНО: Замовлення активоване, вмикаємо швидкий трекінг через Redis Hash
        redisTemplate.opsForHash<String, String>().put("orders:active_drivers", driver.id.toString(), saved.uuid.toString())

        broadcastOrderChange(saved, "ADD") // Оновлюємо статус у всіх
        return TaxiOrderDto(saved)
    }

    @Transactional(noRollbackFor = [ResponseStatusException::class])
fun completeOrder(driver: Driver, orderId: Long): TaxiOrderDto {
    // 1. Сразу чистим Redis Hash
    redisTemplate.opsForHash<String, String>().delete("orders:active_drivers", driver.id.toString())

    val order = orderRepository.findById(orderId)
        .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) } // 👈 ИСПРАВЛЕНО: перенос строки
    
    if (order.driver?.id != driver.id) throw ResponseStatusException(HttpStatus.FORBIDDEN)

    val currentClientPayAmount = order.price - order.appliedDiscount

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
            logger.info(">>> SUCCESS: Capture processed for exact client amount: $currentClientPayAmount")
        } else {
            val isCaptureSuccess = liqPayService.captureFunds(paymentOrderId, order.authorizedAmount)
            
            if (!isCaptureSuccess) {
                order.paymentMethod = "CASH"
                orderRepository.save(order)
                broadcastOrderChange(order, "UPDATE")
                throw ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "Помилка списання базової суми холда. Переведено на ГОТІВКУ.")
            }

            val delta = currentClientPayAmount - order.authorizedAmount
            val token = order.client.cardToken

            if (!token.isNullOrEmpty()) {
                val deltaOrderId = "ride_delta_${order.id}_${System.currentTimeMillis()}"
                val isDeltaSuccess = liqPayService.payWithToken(
                    orderId = deltaOrderId,
                    amount = delta,
                    cardToken = token,
                    description = "Доплата за простій замовлення #${order.id}"
                )
                
                if (!isDeltaSuccess) {
                    notificationService.sendOrderStatusToClient(
                        token = order.client.fcmToken,
                        orderId = order.id!!,
                        status = order.status.name,
                        title = "Помилка оплати простою",
                        body = "Не вдалося списати кошти за простій з картки. Будь ласка, доплатіть водію готівкою: $delta UAH"
                    )
                    
                    order.paymentMethod = "CASH"
                    order.price = delta 
                    orderRepository.save(order)
                    broadcastOrderChange(order, "UPDATE")
                    
                    throw ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "Основна сума списана, але на карті немає грошей за простій. Візьміть з клієнта готівкою: $delta UAH та завершіть ще раз.")
                }
                logger.info(">>> SUCCESS: Hold captured and excess delta ($delta UAH) charged successfully!")
            }
        }
    }
    // =======================================================

    order.status = OrderStatus.COMPLETED
    order.completedAt = LocalDateTime.now()

    if (order.startedAt != null) {
        val realDurationSeconds = java.time.Duration.between(order.startedAt, order.completedAt).toSeconds().toInt()
        order.durationSeconds = if (realDurationSeconds > 0) realDurationSeconds else 1
    }

    driver.completedRides += 1
    driverActivityService.processOrderCompletion(driver, order)
    order.client.tripsCount += 1

    // =======================================================
    // 💰 РАСЧЕТ И СПИСАНИЕ КОМИССИИ ВОДИТЕЛЯ (С ПОЛНОЙ СУММЫ)
    // =======================================================
    val commissionSetting = appSettingRepository.findById("driver_commission_percent").orElse(null)
    val commissionPercent = commissionSetting?.value?.toDoubleOrNull() ?: 10.0
    
    val commissionAmount = order.price * (commissionPercent / 100.0)
    driver.balance = driver.balance - commissionAmount // 👈 ИСПРАВЛЕНО: Явное вычитание типов

    if (order.paymentMethod == "CARD") {
        val finalClientAmount = order.price - order.appliedDiscount
        driver.balance = driver.balance + finalClientAmount // 👈 ИСПРАВЛЕНО: Явное сложение
        
        val rideTransaction = com.taxiapp.server.model.finance.WalletTransaction(
            driver = driver,
            amount = finalClientAmount, 
            operationType = com.taxiapp.server.model.enums.TransactionType.DEPOSIT,
            orderId = order.id,
            description = "Безготівкова оплата замовлення #${order.id}"
        )
        walletTransactionRepository.save(rideTransaction)
    }

    order.commissionAmount = commissionAmount

    if (order.paymentMethod == "CARD") {
        order.bankCommissionAmount = (order.price - order.appliedDiscount) * 0.02 
        order.payoutAmount = (order.price - order.appliedDiscount) - commissionAmount - order.bankCommissionAmount 
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

        if (marketingBalance >= order.appliedDiscount) {
            marketingWalletSetting?.value = (marketingBalance - order.appliedDiscount).toString()
            appSettingRepository.save(marketingWalletSetting)

            driver.balance = driver.balance + order.appliedDiscount // 👈 ИСПРАВЛЕНО

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
        if (order.isPromoCodeUsed) {
            val activePromoUsage = promoCodeService.findActiveUsage(order.client)
            activePromoUsage?.let { promoCodeService.markAsUsed(it.id) }
        } else {
            promoService.markRewardAsUsed(order.client)
        }
    }

    promoService.updateProgressOnRideCompletion(order.client, order)
    
    val savedOrder = orderRepository.save(order)
    chatService.clearChatForOrder(orderId)

    broadcastOrderChange(savedOrder, "UPDATE") 
    
    return TaxiOrderDto(savedOrder)
}
    
    @Scheduled(fixedRate = 60000) // Перевірка кожну хвилину
    @Transactional
    fun activateScheduledOrders() {
        val now = LocalDateTime.now()
        // Шукаємо замовлення, де час подачі настане через 30-35 хвилин (вікно активації)
        // АБО ті, які вже прострочені (для очищення)
        val checkThreshold = now.plusMinutes(35) 

        val pendingOrders = orderRepository.findAllByStatusAndScheduledAtBefore(OrderStatus.SCHEDULED, checkThreshold)

        for (order in pendingOrders) {
            if (order.scheduledAt == null) continue

            // 1. Якщо замовлення безнадійно прострочене (більше години) -> Скасовуємо
            if (order.scheduledAt!!.isBefore(now.minusHours(1))) {
                logger.warn("Scheduled order ${order.id} expired. Cancelling.")
                order.status = OrderStatus.CANCELLED
                orderRepository.save(order)
                
                // <--- ОЧИСТКА ЧАТА --->
                chatService.clearChatForOrder(order.id!!)
                
                continue
            }

            // Ми працюємо тільки з тими, де до подачі залишилось <= 30 хвилин
            // (але більше ніж -1 година, щоб не чіпати старі)
            if (order.scheduledAt!!.isAfter(now.plusMinutes(31))) {
                // Ще рано (більше 31 хвилини до подачі)
                continue 
            }

            // === ЛОГІКА ПІДТВЕРДЖЕННЯ (ЗА 30 ХВ) ===
            
            if (order.driver != null) {
                // ВИПРАВЛЕНО: Додано "== true", бо поле може бути null
                if (order.isDriverConfirmed == true) {
                    if (order.status == OrderStatus.SCHEDULED) {
                        order.status = OrderStatus.ACCEPTED
                        orderRepository.save(order)
                        broadcastOrderChange(order, "ADD")
                    }
                    continue
                }

                // Водій є, але ще не підтвердив
                if (order.confirmationRequestedAt == null) {
                logger.info("Asking confirmation for order #${order.id}")
                order.confirmationRequestedAt = LocalDateTime.now()
                orderRepository.save(order)
                
                // ВИПРАВЛЕНО: Викликаємо новий метод сповіщення
                notificationService.sendConfirmationRequest(order.driver!!, order) 
            } else {
                    // КРОК 2: Перевіряємо тайм-аут (1 хвилина)
                    if (now.isAfter(order.confirmationRequestedAt!!.plusMinutes(1))) {
                        logger.warn("Driver ${order.driver!!.id} failed to confirm order #${order.id}. PENALTY applied.")
                        
                        // ШТРАФ
                        driverActivityService.processOrderCancellation(order.driver!!, order.id!!, 50, "Не підтвердив заплановане")
                        
                        // Знімаємо водія
                        order.rejectedDriverIds.add(order.driver!!.id!!)
                        order.driver = null
                        order.isDriverConfirmed = false
                        order.confirmationRequestedAt = null
                        
                        // Статус залишається SCHEDULED, але тепер без водія -> піде в пошук
                        val saved = orderRepository.save(order)
                        broadcastOrderChange(saved, "ADD") // Повертається в ефір
                    }
                }
            } 
            // === ЛОГІКА ПОШУКУ НОВОГО ВОДІЯ ===
            else {
                // Водія немає, час підтискає (<= 30 хв). Шукаємо активно.
                logger.info("Searching driver for scheduled order #${order.id}")
                
                // Ставимо REQUESTED/OFFERING щоб запустити механізм торгів, 
                // але статус самої сутності в базі краще тримати SCHEDULED або REQUESTED
                
                // Спробуємо знайти водія
                val scheduledThreshold = LocalDateTime.now().minusSeconds(45)
                val bestDriver = driverRepository.findBestDriverForOrder(
                    pickupLat = order.originLat ?: 0.0,
                    pickupLng = order.originLng ?: 0.0,
                    destinationSectorId = order.destinationSector?.id,
                    rejectedDriverIds = order.rejectedDriverIds.toList(),
                    lastSeenThreshold = scheduledThreshold // 👈 Передаем порог
                ).orElse(null)

                if (bestDriver != null) {
                    logger.info("Offering scheduled order #${order.id} to ${bestDriver.id}")
                    order.status = OrderStatus.OFFERING
    order.offeredDriver = bestDriver
    val expiresAt = LocalDateTime.now().plusSeconds(20)
    order.offerExpiresAt = expiresAt
    
    // 🔥 ВНЕДРЯЕМ СЮДА ТОЖЕ:
    val epochSecond = expiresAt.toEpochSecond(java.time.ZoneOffset.UTC)
    redisTemplate.opsForZSet().add("orders:expired_offers", order.id.toString(), epochSecond.toDouble())

    notificationService.sendOrderOffer(bestDriver, order)
    orderRepository.save(order)
                } else {
                    // Нікого немає - просто висить в ефірі
                    if (order.status != OrderStatus.REQUESTED) {
                        order.status = OrderStatus.REQUESTED
                        orderRepository.save(order)
                        broadcastOrderChange(order, "ADD")
                    }
                }
            }
        }
    }

    @Transactional
    fun updatePaymentMethod(orderId: Long, method: String) {
        val order = orderRepository.findById(orderId) 
            .orElseThrow { RuntimeException("Замовлення не знайдено") }

        // Обновляем метод оплаты
        order.paymentMethod = method
        val savedOrder = orderRepository.save(order)

        // Используем твой готовый метод broadcastOrderChange, 
        // который уже умеет отправлять нужные сокеты диспетчерам, клиенту и водителям!
        broadcastOrderChange(savedOrder, "UPDATE")
    }

    @Transactional
    fun updatePrice(orderId: Long, addedValue: Double) {
        val order = orderRepository.findById(orderId)
            .orElseThrow { RuntimeException("Замовлення не знайдено") }

        // Отнимаем старую надбавку и прибавляем новую
        val basePriceWithoutExtra = order.price - order.addedValue
        order.addedValue = addedValue
        order.price = basePriceWithoutExtra + addedValue 
        
        val savedOrder = orderRepository.save(order)

        // Аналогично, используем твой броадкаст!
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
        OrderStatus.ARRIVED_AT_WAYPOINT // 👈 ДОБАВЛЕНО, чтобы база данных возвращала этот статус диспетчеру!
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
        val order = orderRepository.findById(orderId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено") }

        if (order.driver?.id != driver.id) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Це не ваше замовлення")
        if (order.status != OrderStatus.IN_PROGRESS) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Не можна відзначити прибуття на точку в цьому статусі")
        }

        order.status = OrderStatus.ARRIVED_AT_WAYPOINT
        order.currentStopOrder += 1
        order.waypointArrivedAt = LocalDateTime.now()

        val savedOrder = orderRepository.save(order)
        
        // Мгновенно обновляем всех (водителя, клиента, диспетчера) через твои чистые сокеты
        broadcastOrderChange(savedOrder, "UPDATE")

        // Отправляем Push-уведомление клиенту
        val currentStopAddress = savedOrder.stops.sortedBy { it.stopOrder }.find { it.stopOrder == savedOrder.currentStopOrder }?.address ?: ""
        notificationService.sendOrderStatusToClient(
            token = savedOrder.client.fcmToken,
            orderId = savedOrder.id!!,
            status = savedOrder.status.name, // "ARRIVED_AT_WAYPOINT"
            title = "Водій на проміжній точці",
            body = "Очікуйте, водій прибув на зупинку: $currentStopAddress"
        )

        return TaxiOrderDto(savedOrder)
    }

    @Transactional
    fun resumeTrip(driver: Driver, orderId: Long): TaxiOrderDto {
        val order = orderRepository.findById(orderId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено") }

        if (order.driver?.id != driver.id) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Це не ваше замовлення")
        if (order.status != OrderStatus.ARRIVED_AT_WAYPOINT) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Поїздку неможливо продовжити з цього стану")
        }

        // --- ЛОГИКА РАСЧЕТА СТОИМОСТИ ОЖИДАНИЯ НА ТОЧКЕ ---
        if (order.waypointArrivedAt != null) {
            val minutesWaited = maxOf(0, java.time.temporal.ChronoUnit.MINUTES.between(order.waypointArrivedAt!!, LocalDateTime.now()).toInt())
            val freeMinutes = order.tariff.freeWaitingMinutes

            if (minutesWaited > freeMinutes) {
                val paidMinutes = minutesWaited - freeMinutes
                val extraCost = paidMinutes * order.tariff.pricePerWaitingMinute

                order.waitingPrice += extraCost
                order.price += extraCost // Плюсуем простой к общей стоимости заказа
                logger.info("[WAYPOINT_WAITING] Заказ ${order.id}: платное ожидание $paidMinutes мин. Добавлено: $extraCost UAH")
            }
        }

        // Возвращаем заказ в статус поездки
        order.status = OrderStatus.IN_PROGRESS
        order.waypointArrivedAt = null // Сбрасываем таймер точки для следующей остановки (если она есть)

        val savedOrder = orderRepository.save(order)
        broadcastOrderChange(savedOrder, "UPDATE")

        notificationService.sendOrderStatusToClient(
            token = savedOrder.client.fcmToken,
            orderId = savedOrder.id!!,
            status = savedOrder.status.name, // "IN_PROGRESS"
            title = "Рушаємо далі",
            body = "Поїздка продовжилась."
        )

        return TaxiOrderDto(savedOrder)
    }

    fun mapToDto(order: TaxiOrder): TaxiOrderDto = TaxiOrderDto(order)
}