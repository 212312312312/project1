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

    // --- ОТПРАВКА ЗАКАЗА В EVOS ---
    fun sendOrderToEvoS(order: TaxiOrder): String? {
        if (!settingsService.isEvosEnabled()) return null

        val baseUrl = settingsService.getEvosUrl().trimEnd('/')
        val url = "$baseUrl/weborders"

        // 1. Форматирование комментарий и логика скидки/доплаты
        val fullPrice = order.price // Полная стоимость для водителя EvoS
        val discount = order.appliedDiscount
        val clientPays = fullPrice - discount

        val commentBuilder = StringBuilder()
        if (discount > 0.0) {
            commentBuilder.append("[Служба] Клієнт платить: ${clientPays.toInt()}грн, Доплата: ${discount.toInt()}грн.")
        }
        if (!order.comment.isNullOrBlank()) {
            if (commentBuilder.isNotEmpty()) commentBuilder.append(" ")
            commentBuilder.append(order.comment)
        }

        // 2. Формирование маршрута
        val routeList = mutableListOf<EvoSRoutePointDto>()
        routeList.add(
            EvoSRoutePointDto(
                name = order.fromAddress,
                lat = order.originLat,
                lng = order.originLng
            )
        )

        // Промежуточные остановки
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

        val clientPhone = order.client.userPhone ?: "0000000000"
        val clientName = if (order.client.fullName.isNotBlank()) order.client.fullName else "Клієнт"

        val body = EvoSCreateOrderRequestDto(
            userFullName = if (clientName.isBlank()) "Пасажир" else clientName,
            userPhone = clientPhone,
            comment = commentBuilder.toString(),
            addCost = 0.0,
            route = routeList,
            taxiColumnId = 0,
            paymentType = 0 // Наличный расчёт для стороннего водителя
        )

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
        val url = "$baseUrl/weborders/$evosOrderUid"

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
        val url = "$baseUrl/weborders/drivercarposition/$evosOrderUid"

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
        val url = "$baseUrl/weborders/cancel/$evosOrderUid"

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