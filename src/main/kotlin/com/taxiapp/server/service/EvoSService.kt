package com.taxiapp.server.service

import com.taxiapp.server.dto.evos.*
import com.taxiapp.server.model.order.TaxiOrder
import org.slf4j.LoggerFactory
import org.springframework.http.*
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import java.security.MessageDigest
import java.util.Base64

@Service
class EvoSService(
    private val settingsService: SettingsService
) {
    private val logger = LoggerFactory.getLogger(EvoSService::class.java)
    private val restTemplate = RestTemplate()

    // Хеширование пароля SHA-512 по спецификации EvoS
    private fun computeSha512Hash(password: String): String {
        val bytes = MessageDigest.getInstance("SHA-512").digest(password.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // Формирование HTTP заголовков авторизации
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
        return headers
    }

    // --- 1. ПРЕДВАРИТЕЛЬНЫЙ РАСЧЕТ БАЗОВОЙ СТОИМОСТИ В EVOS ---
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

    // --- 2. СОЗДАНИЕ ЗАКАЗА С ГАРАНТИРОВАННОЙ НАШЕЙ ЦЕНОЙ ---
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
            it.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        }

        var hasAnimal = false
        var hasBaggage = false
        var hasConditioner = false
        var hasCourier = false
        var hasWagon = false

        val extraServiceNames = mutableListOf<String>()
        val extraCodesList = mutableListOf<String>()

        order.selectedServices.forEach { service ->
            extraServiceNames.add(service.name)
            if (!service.evosCode.isNullOrBlank()) {
                val code = service.evosCode!!
                extraCodesList.add(code)
                when (code) {
                    "ANIMAL" -> hasAnimal = true
                    "BAGGAGE" -> hasBaggage = true
                    "CONDIT" -> hasConditioner = true
                    "COURIER" -> hasCourier = true
                    "WAGON" -> hasWagon = true
                }
            }
        }

        val discount = order.appliedDiscount
        val clientPays = order.price - discount

        val commentBuilder = StringBuilder()
        if (discount > 0.0) {
            commentBuilder.append("[Служба] Клієнт платить: ${clientPays.toInt()}грн, Доплата: ${discount.toInt()}грн.")
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

        // Черновой DTO для запроса базовой стоимости в EvoS
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
            extraChargeCodes = extraCodesList.ifEmpty { null },
            route = routeList,
            taxiColumnId = 0,
            paymentType = pType
        )

        // 🟢 РАСЧЕТ РАЗНИЦЫ: Вычисляем add_cost для фиксации нашей цены
        val evosBaseCost = calculateEvoSCost(draftBody) ?: 0.0
        val priceDiff = if (order.price > evosBaseCost && evosBaseCost > 0.0) {
            order.price - evosBaseCost
        } else {
            0.0
        }
        val finalAddCost = priceDiff + order.addedValue

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

    // --- 3. ОБНОВЛЕНИЕ ДОБАВОЧНОЙ СТОИМОСТИ (КОГДА КЛИЕНТ ПОДНИМАЕТ ЦЕНУ В ПРИЛОЖЕНИИ) ---
    fun updateAdditionalCost(evosOrderUid: String, newAddCost: Double): Boolean {
        val baseUrl = settingsService.getEvosUrl().trimEnd('/')
        val url = "$baseUrl/api/weborders/$evosOrderUid/cost/additional"

        return try {
            val requestEntity = HttpEntity(EvoSAddCostRequestDto(newAddCost), createHeaders())
            restTemplate.exchange(url, HttpMethod.PUT, requestEntity, Void::class.java)
            logger.info(">>> [EvoS] Добавочна вартість для $evosOrderUid оновлена: $newAddCost грн")
            true
        } catch (e: Exception) {
            logger.error(">>> [EvoS] Помилка оновлення добавочної вартості: ${e.message}")
            false
        }
    }

    // --- ЗАПРОС СОСТОЯНИЯ ЗАКАЗА ---
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

    // --- ЗАПРОС GPS ПОЛОЖЕНИЯ МАШИНЫ ---
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

    // --- ЗАПРОС ОТМЕНЫ ЗАКАЗА ---
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