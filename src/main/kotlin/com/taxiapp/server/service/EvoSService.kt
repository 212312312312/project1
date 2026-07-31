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

    fun sendOrderToEvoS(order: TaxiOrder): String? {
    if (!settingsService.isEvosEnabled()) return null

    val baseUrl = settingsService.getEvosUrl().trimEnd('/')
    val url = "$baseUrl/api/weborders"

    // 1. Имя и телефон клиента
    val clientPhone = order.client.userPhone ?: "0000000000"
    val clientName = if (order.client.fullName.isNotBlank()) order.client.fullName else "Пасажир"

    // 2. Тип оплаты (0 - Наличные, 1 - Безнал/Карта)
    val pType = if (order.paymentMethod == "CARD") 1 else 0

    // 3. Предварительный заказ (Заказ на время)
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

    // 5. Формирование подробного комментария (Скидка + Список услуг + Текст клиента)
    val fullPrice = order.price
    val discount = order.appliedDiscount
    val clientPays = fullPrice - discount

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

    // 6. Формирование точки А, промежуточных и точки Б
    val routeList = mutableListOf<EvoSRoutePointDto>()
    routeList.add(
        EvoSRoutePointDto(
            name = order.fromAddress,
            lat = order.originLat,
            lng = order.originLng
        )
    )

    order.stops.sortedBy { it.stopOrder }.forEach { stop ->
        routeList.add(
            EvoSRoutePointDto(
                name = stop.address,
                lat = stop.lat,
                lng = stop.lng
            )
        )
    }

    routeList.add(
        EvoSRoutePointDto(
            name = order.toAddress,
            lat = order.destLat,
            lng = order.destLng
        )
    )

    // 7. Сборка полного DTO
    val body = EvoSCreateOrderRequestDto(
        userFullName = clientName,
        userPhone = clientPhone,
        requiredTime = formattedRequiredTime,
        reservation = isReservation,
        comment = commentBuilder.toString(),
        addCost = 0.0,
        orderCost = order.price,
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

    // 8. Отправка на бэкенд партнеров
    return try {
        val requestEntity = HttpEntity(body, createHeaders())
        val response = restTemplate.postForEntity(url, requestEntity, EvoSCreateOrderResponseDto::class.java)

        if (response.statusCode == HttpStatus.OK && response.body != null) {
            val uid = response.body!!.dispatchingOrderUid
            logger.info(">>> [EvoS] Заказ #${order.id} успешно перекинут в EvoS. UID: $uid")
            uid
        } else {
            logger.error(">>> [EvoS] Ошибка отправки заказа #${order.id}: ${response.statusCode}")
            null
        }
    } catch (e: Exception) {
        logger.error(">>> [EvoS] Исключение при отправке заказа #${order.id} в EvoS: ${e.message}")
        null
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