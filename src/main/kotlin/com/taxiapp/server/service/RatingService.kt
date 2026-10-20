package com.taxiapp.server.service

import com.taxiapp.server.model.enums.OrderStatus
import com.taxiapp.server.model.rating.OrderRating
import com.taxiapp.server.model.rating.RatingSource
import com.taxiapp.server.model.user.User
import com.taxiapp.server.repository.*
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

@Service
class RatingService(
    private val orderRatingRepository: OrderRatingRepository,
    private val taxiOrderRepository: TaxiOrderRepository,
    private val driverRepository: DriverRepository,
    private val clientRepository: ClientRepository,
    private val userRepository: UserRepository
) {

    // --- ОЦЕНКА ВОДИТЕЛЯ (Делает Клиент) ---
    @Transactional
    fun rateDriver(clientUser: User, orderIdStr: String, score: Int, comment: String?) {
        if (score !in 1..5) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Оцінка повинна бути від 1 до 5")
        }

        val order = try {
            val uuid = java.util.UUID.fromString(orderIdStr)
            taxiOrderRepository.findByUuid(uuid).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено") }
        } catch (e: IllegalArgumentException) {
            val idLong = orderIdStr.toLongOrNull() ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Невірний формат ID")
            taxiOrderRepository.findById(idLong).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено") }
        }

        if (order.client.id != clientUser.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Ви не можете оцінити чуже замовлення")
        }
        if (order.status != OrderStatus.COMPLETED) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Оцінити можна тільки завершену поїздку")
        }
        if (order.driver == null) {
            // Если это партнерский заказ (EvoS) без системного водителя — просто помечаем оцененным без ошибки
            order.isRatedByClient = true
            taxiOrderRepository.save(order)
            return
        }
        if (order.isRatedByClient) {
            // Идемпотентный выход, чтобы не бросать 409 при повторной отправке
            return
        }

        // 1. Создаем рейтинг
        val rating = OrderRating(
            order = order,
            source = RatingSource.CLIENT,
            targetUser = order.driver!!,
            score = score,
            comment = comment?.take(1000)
        )
        orderRatingRepository.save(rating)

        // 2. Обновляем статус заказа
        order.isRatedByClient = true
        taxiOrderRepository.save(order)

        // 3. Пересчитываем рейтинг водителя (последние 100)
        recalculateDriverRating(order.driver!!.id!!)
    }

    // --- ОЦЕНКА КЛИЕНТА (Делает Водитель) ---
    @Transactional
    fun rateClient(driverUser: User, orderIdStr: String, score: Int, comment: String?) {
        if (score !in 1..5) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Оцінка повинна бути від 1 до 5")
        }

        val order = try {
            val uuid = java.util.UUID.fromString(orderIdStr)
            taxiOrderRepository.findByUuid(uuid).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено") }
        } catch (e: IllegalArgumentException) {
            val idLong = orderIdStr.toLongOrNull() ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Невірний формат ID")
            taxiOrderRepository.findById(idLong).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено") }
        }

        if (order.driver?.id != driverUser.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Ви не були водієм цього замовлення")
        }
        if (order.status != OrderStatus.COMPLETED) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Оцінити можна тільки завершену поїздку")
        }
        if (order.isRatedByDriver) throw ResponseStatusException(HttpStatus.CONFLICT, "Ви вже оцінили цього пасажира")

        // 1. Создаем рейтинг
        val rating = OrderRating(
            order = order,
            source = RatingSource.DRIVER,
            targetUser = order.client,
            score = score,
            comment = comment?.take(1000)
        )
        orderRatingRepository.save(rating)

        // 2. Обновляем статус заказа
        order.isRatedByDriver = true
        taxiOrderRepository.save(order)

        // 3. Пересчитываем рейтинг клиента (последние 40)
        recalculateClientRating(order.client.id!!)
    }

    // --- ЛОГИКА ПЕРЕСЧЕТА ---

    private fun recalculateDriverRating(driverId: Long) {
        val lastRatings = orderRatingRepository.findByTargetUserIdAndSourceAndIsIgnoredFalseOrderByCreatedAtDesc(
            driverId, RatingSource.CLIENT, PageRequest.of(0, 100)
        )

        if (lastRatings.isEmpty()) return

        val avg = lastRatings.map { it.score }.average()
        val count = lastRatings.size

        val driver = driverRepository.findById(driverId).orElseThrow()
        driver.rating = String.format(java.util.Locale.US, "%.2f", avg).toDouble()
        driver.ratingCount = count
        driverRepository.save(driver)
    }

    private fun recalculateClientRating(clientId: Long) {
        val lastRatings = orderRatingRepository.findByTargetUserIdAndSourceAndIsIgnoredFalseOrderByCreatedAtDesc(
            clientId, RatingSource.DRIVER, PageRequest.of(0, 40)
        )

        if (lastRatings.isEmpty()) return

        val avg = lastRatings.map { it.score }.average()
        val count = lastRatings.size

        val client = clientRepository.findById(clientId).orElseThrow()
        client.rating = String.format(java.util.Locale.US, "%.2f", avg).toDouble()
        client.ratingCount = count
        clientRepository.save(client)
    }

    // --- ДЛЯ АДМИНКИ ---

    @Transactional
    fun toggleIgnoreRating(ratingId: Long) {
        val rating = orderRatingRepository.findById(ratingId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Рейтинг не знайдено") }

        rating.isIgnored = !rating.isIgnored
        orderRatingRepository.save(rating)

        if (rating.source == RatingSource.CLIENT) {
            recalculateDriverRating(rating.targetUser.id!!)
        } else {
            recalculateClientRating(rating.targetUser.id!!)
        }
    }

    fun getAllRatings(): List<OrderRating> {
        return orderRatingRepository.findAllByOrderByCreatedAtDesc()
    }
}