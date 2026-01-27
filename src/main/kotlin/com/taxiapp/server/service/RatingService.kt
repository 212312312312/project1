package com.taxiapp.server.service

import com.taxiapp.server.model.rating.OrderRating
import com.taxiapp.server.model.rating.RatingSource
import com.taxiapp.server.repository.*
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

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
    fun rateDriver(orderId: Long, score: Int, comment: String?) {
        val order = taxiOrderRepository.findById(orderId)
            .orElseThrow { RuntimeException("Замовлення не знайдено") }

        if (order.driver == null) throw RuntimeException("У замовлення немає водія")
        if (order.isRatedByClient) throw RuntimeException("Ви вже оцінили цю поїздку")

        // 1. Создаем рейтинг
        val rating = OrderRating(
            order = order,
            source = RatingSource.CLIENT, // Оценил клиент
            targetUser = order.driver!!,  // Оценили водителя
            score = score,
            comment = comment
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
    fun rateClient(orderId: Long, score: Int, comment: String?) {
        val order = taxiOrderRepository.findById(orderId)
            .orElseThrow { RuntimeException("Замовлення не знайдено") }

        if (order.isRatedByDriver) throw RuntimeException("Ви вже оцінили цього пасажира")

        // 1. Создаем рейтинг
        val rating = OrderRating(
            order = order,
            source = RatingSource.DRIVER, // Оценил водитель
            targetUser = order.client,    // Оценили клиента
            score = score,
            comment = comment
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
        // Берем последние 100 оценок (источник CLIENT -> значит оценили водителя)
        val lastRatings = orderRatingRepository.findByTargetUserIdAndSourceAndIsIgnoredFalseOrderByCreatedAtDesc(
            driverId, RatingSource.CLIENT, PageRequest.of(0, 100)
        )
        
        if (lastRatings.isEmpty()) return

        val avg = lastRatings.map { it.score }.average()
        val count = lastRatings.size // или countTotal, если нужно общее число за всё время

        val driver = driverRepository.findById(driverId).orElseThrow()
        driver.rating = String.format("%.2f", avg).replace(',', '.').toDouble()
        driver.ratingCount = count
        driverRepository.save(driver)
    }

    private fun recalculateClientRating(clientId: Long) {
        // Берем последние 40 оценок (источник DRIVER -> значит оценили клиента)
        val lastRatings = orderRatingRepository.findByTargetUserIdAndSourceAndIsIgnoredFalseOrderByCreatedAtDesc(
            clientId, RatingSource.DRIVER, PageRequest.of(0, 40)
        )

        if (lastRatings.isEmpty()) return

        val avg = lastRatings.map { it.score }.average()
        val count = lastRatings.size

        val client = clientRepository.findById(clientId).orElseThrow()
        client.rating = String.format("%.2f", avg).replace(',', '.').toDouble()
        client.ratingCount = count
        clientRepository.save(client)
    }

    // --- ДЛЯ АДМИНКИ ---
    
    @Transactional
    fun toggleIgnoreRating(ratingId: Long) {
        val rating = orderRatingRepository.findById(ratingId)
            .orElseThrow { RuntimeException("Рейтинг не знайдено") }
        
        rating.isIgnored = !rating.isIgnored
        orderRatingRepository.save(rating)

        // Пересчет
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