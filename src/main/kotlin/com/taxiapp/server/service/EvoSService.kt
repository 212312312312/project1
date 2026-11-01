package com.taxiapp.server.service

import com.taxiapp.server.dto.evos.*
import com.taxiapp.server.model.order.TaxiOrder
import org.slf4j.LoggerFactory
import org.springframework.http.*
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import java.security.MessageDigest
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale

@Service
class EvoSService(
    private val settingsService: SettingsService
) {
    private val logger = LoggerFactory.getLogger(EvoSService::class.java)

    // HIGH-LOAD ФИКС: Жесткие таймауты на подключение (3 сек) и чтение (5 сек).
    // Без них зависание внешнего сервера EvoS навсегда блокирует потоки шедулера и пул соединений!
    private val restTemplate = RestTemplate(
        SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(3000)
            setReadTimeout(5000)
        }
    )

    private fun computeSha512Hash(password: String): String {
        val bytes = MessageDigest.getInstance("SHA-512").digest(password.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun createHeaders(): HttpHeaders {
        val login = settingsService.getEvosLogin()
        val password = settingsService.getEvosPassword()
        val appId = settingsService.getEvosAppId()

        val passwordHash = computeSha512Hash(password)
        val rawAuth = "$login:$passwordHash"
        val basicAuth = Base64.getEncoder().encodeToString(rawAuth.toByteArray(Charsets.UTF_8))

        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON
        headers.set("Accept", "application/json")
        headers.set("Authorization", "Basic $basicAuth")
        headers.set("X-WO-API-APP-ID", appId)
        headers.set("X-API-VERSION", "1.46.0")
        return headers
    }

    fun calculateEvoSCost(body: EvoSCreateOrderRequestDto): Double? {
        val baseUrl = settingsService.getEvosUrl().trimEnd('/')
        val url = "$baseUrl/api/weborders/cost"

        return try {
            val requestEntity = HttpEntity(body, createHeaders())
            val response = restTemplate.postForEntity(url, requestEntity, EvoSCalculateCostResponseDto::class.java)
            response.body?.orderCost?.toDoubleOrNull()
        } catch (e: Exception) {
            logger.warn(">>> [EvoS] Не удалось предварительно рассчитать стоимость: ${e.message}")
            null
        }
    }

    fun sendOrderToEvoS(order: TaxiOrder): String? {
        if (!settingsService.isEvosEnabled()) return null

        val baseUrl = settingsService.getEvosUrl().trimEnd('/')
        val url = "$baseUrl/api/weborders"

        val clientPhone = order.client.userPhone ?: "0000000000"
        val clientName = if (order.client.fullName.isNotBlank()) order.client.fullName else "Пасажир"

        val isCardPayment = order.paymentMethod in listOf("CARD", "CARD_TO_DRIVER", "DRIVER_CARD", "CARD_TRANSFER", "ONLINE")
        val pType = if (isCardPayment) 1 else 0

        val isReservation = order.scheduledAt != null
        val formattedRequiredTime = order.scheduledAt?.let {
            it.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
        }

        var hasAnimal = false
        var hasBaggage = false
        var hasConditioner = false
        var hasCourier = false
        var hasWagon = false
        var hasTerminal = false
        var hasReceipt = false

        val extraServiceNames = mutableListOf<String>()
        val extraCodesList = mutableListOf<String>()

        order.selectedServices.forEach { service ->
            extraServiceNames.add(service.name)
            if (!service.evosCode.isNullOrBlank()) {
                val code = service.evosCode!!.trim().uppercase()
                extraCodesList.add(code)
                when (code) {
                    "ANIMAL" -> hasAnimal = true
                    "BAGGAGE" -> hasBaggage = true
                    "CONDIT" -> hasConditioner = true
                    "COURIER" -> hasCourier = true
                    "WAGON" -> hasWagon = true
                    "TERMINAL" -> hasTerminal = true
                    "CHECK" -> hasReceipt = true
                }
            }
        }

        val discount = Math.round(order.appliedDiscount).toInt()
        val clientPays = Math.round(order.price - order.appliedDiscount).toInt()

        val commentBuilder = StringBuilder()
        if (discount > 0) {
            commentBuilder.append("[Служба] Клієнт платить: ${clientPays}грн, Доплата: ${discount}грн.")
        }
        if (extraServiceNames.isNotEmpty()) {
            if (commentBuilder.isNotEmpty()) commentBuilder.append(" ")
            commentBuilder.append("[Послуги: ${extraServiceNames.joinToString(", ")}].")
        }
        if (!order.comment.isNullOrBlank()) {
            if (commentBuilder.isNotEmpty()) commentBuilder.append(" ")
            commentBuilder.append(order.comment)
        }

        val routeList = mutableListOf<EvoSRoutePointDto>()
        routeList.add(EvoSRoutePointDto(name = order.fromAddress, lat = order.originLat, lng = order.originLng))
        order.stops.sortedBy { it.stopOrder }.forEach { stop ->
            routeList.add(EvoSRoutePointDto(name = stop.address, lat = stop.lat, lng = stop.lng))
        }
        routeList.add(EvoSRoutePointDto(name = order.toAddress, lat = order.destLat, lng = order.destLng))

        val draftBody = EvoSCreateOrderRequestDto(
            userFullName = clientName,
            userPhone = clientPhone,
            requiredTime = formattedRequiredTime,
            reservation = isReservation,
            comment = commentBuilder.toString(),
            addCost = 0.0,
            wagon = hasWagon,
            animal = hasAnimal,
            baggage = hasBaggage,
            conditioner = hasConditioner,
            courierDelivery = hasCourier,
            terminal = hasTerminal,
            receipt = hasReceipt,
            flexibleTariffName = order.tariff.evosTariffName,
            extraChargeCodes = extraCodesList.ifEmpty { null },
            route = routeList,
            taxiColumnId = 1,
            paymentType = pType
        )

        val evosBaseCost = calculateEvoSCost(draftBody) ?: 0.0
        order.evosBaseCost = evosBaseCost

        val finalAddCost = if (order.price > evosBaseCost && evosBaseCost > 0.0) {
            order.price - evosBaseCost
        } else {
            0.0
        }

        logger.info(">>> [EvoS Price Matching] Наша ціна: ${order.price} грн, База EvoS: $evosBaseCost грн, Розрахований add_cost: $finalAddCost грн")

        val finalBody = draftBody.copy(addCost = finalAddCost)

        return try {
            val requestEntity = HttpEntity(finalBody, createHeaders())
            val response = restTemplate.postForEntity(url, requestEntity, EvoSCreateOrderResponseDto::class.java)

            if (response.statusCode == HttpStatus.OK && response.body != null) {
                val uid = response.body!!.dispatchingOrderUid
                logger.info(">>> [EvoS] Замовлення #${order.id} успішно створено в EvoS з нашою ціною! UID: $uid")
                uid
            } else {
                logger.error(">>> [EvoS] Помилка створення замовлення #${order.id}: ${response.statusCode}")
                null
            }
        } catch (e: Exception) {
            logger.error(">>> [EvoS] Виняток при відправці замовлення #${order.id} в EvoS: ${e.message}")
            null
        }
    }

    fun getNearbyDrivers(lat: Double, lng: Double, radiusKm: Double = 3.0): List<EvoSDriverPositionItemDto> {
        if (!settingsService.isEvosEnabled()) return emptyList()

        val baseUrl = settingsService.getEvosUrl().trimEnd('/')
        val intRadius = radiusKm.toInt().coerceAtLeast(1)
        val formattedLat = String.format(Locale.US, "%.6f", lat)
        val formattedLng = String.format(Locale.US, "%.6f", lng)
        val url = "$baseUrl/api/drivers/position?lat=$formattedLat&lng=$formattedLng&radius=$intRadius"

        return try {
            val requestEntity = HttpEntity<Void>(createHeaders())
            val response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                requestEntity,
                Array<EvoSDriverPositionItemDto>::class.java
            )
            val items = response.body?.toList() ?: emptyList()
            if (items.isNotEmpty()) {
                val sample = items.first()
                logger.info(
                    ">>> [EvoS Raw Positions] Отримано від EvoS машин: ${items.size} (radius=${intRadius}km, lat=$formattedLat, lng=$formattedLng). " +
                    "Приклад [0]: state=${sample.state}, posStatus=${sample.drivercarPosition?.status}, lat=${sample.drivercarPosition?.lat}, lng=${sample.drivercarPosition?.lng}"
                )
            } else {
                logger.info(">>> [EvoS Raw Positions] Отримано від EvoS машин: 0 (radius=${intRadius}km, lat=$formattedLat, lng=$formattedLng)")
            }
            items
        } catch (e: Exception) {
            logger.error(">>> [EvoS] Помилка отримання координат водіїв з EvoS ($url): ${e.message}")
            emptyList()
        }
    }

    fun updateOrderPriceInEvoS(order: TaxiOrder): Boolean {
        val evosOrderUid = order.evosOrderUid ?: return false
        val evosBase = order.evosBaseCost

        val targetAddCost = if (order.price > evosBase && evosBase > 0.0) {
            order.price - evosBase
        } else {
            0.0
        }

        logger.info(">>> [EvoS Price Update] Оновлення ціни для $evosOrderUid: Нова ціна=${order.price} грн, База EvoS=$evosBase грн -> Відправляємо add_cost=$targetAddCost грн")
        return updateAdditionalCost(evosOrderUid, targetAddCost)
    }

    fun getEvoSTariffs(): List<String> {
        if (!settingsService.isEvosEnabled()) return emptyList()
        val baseUrl = settingsService.getEvosUrl().trimEnd('/')
        val url = "$baseUrl/api/tariffs"

        return try {
            val requestEntity = HttpEntity<Void>(createHeaders())
            val response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                requestEntity,
                Array<EvoSTariffItemDto>::class.java
            )
            response.body?.map { it.name } ?: emptyList()
        } catch (e: Exception) {
            logger.error(">>> [EvoS] Помилка отримання списку тарифів з EvoS: ${e.message}")
            emptyList()
        }
    }

    fun updateAdditionalCost(evosOrderUid: String, newAddCost: Double): Boolean {
        val baseUrl = settingsService.getEvosUrl().trimEnd('/')
        val url = "$baseUrl/api/weborders/$evosOrderUid/cost/additional"

        return try {
            val requestDto = EvoSAddCostRequestDto(
                id = evosOrderUid,
                amount = newAddCost
            )
            val requestEntity = HttpEntity(requestDto, createHeaders())
            restTemplate.exchange(url, HttpMethod.PUT, requestEntity, Void::class.java)
            logger.info(">>> [EvoS] Добавочна вартість для $evosOrderUid оновлена на $newAddCost грн (amount=$newAddCost)")
            true
        } catch (e: Exception) {
            logger.error(">>> [EvoS] Помилка оновлення добавочної вартості: ${e.message}")
            false
        }
    }

    fun getOrderState(evosOrderUid: String): EvoSOrderStateResponseDto? {
        val baseUrl = settingsService.getEvosUrl().trimEnd('/')
        val url = "$baseUrl/api/weborders/$evosOrderUid"

        return try {
            val requestEntity = HttpEntity<Void>(createHeaders())
            val response = restTemplate.exchange(url, HttpMethod.GET, requestEntity, EvoSOrderStateResponseDto::class.java)
            response.body
        } catch (e: Exception) {
            logger.error(">>> [EvoS] Ошибка получения состояния заказа $evosOrderUid: ${e.message}")
            null
        }
    }

    fun getDriverPosition(evosOrderUid: String): EvoSDriverCarPositionDto? {
        val baseUrl = settingsService.getEvosUrl().trimEnd('/')
        val url = "$baseUrl/api/weborders/drivercarposition/$evosOrderUid"

        return try {
            val requestEntity = HttpEntity<Void>(createHeaders())
            val response = restTemplate.exchange(url, HttpMethod.GET, requestEntity, EvoSDriverCarPositionDto::class.java)
            response.body
        } catch (e: Exception) {
            null
        }
    }

    fun cancelOrderInEvoS(evosOrderUid: String): Boolean {
        val baseUrl = settingsService.getEvosUrl().trimEnd('/')
        val url = "$baseUrl/api/weborders/cancel/$evosOrderUid"

        return try {
            val requestEntity = HttpEntity<Void>(createHeaders())
            val response = restTemplate.exchange(url, HttpMethod.PUT, requestEntity, EvoSCancelResponseDto::class.java)
            val result = response.body?.orderClientCancelResult
            logger.info(">>> [EvoS] Отмена заказа $evosOrderUid в EvoS. Результат: $result")
            result == 1 || result == 2
        } catch (e: Exception) {
            logger.error(">>> [EvoS] Ошибка отмены заказа $evosOrderUid в EvoS: ${e.message}")
            false
        }
    }
}