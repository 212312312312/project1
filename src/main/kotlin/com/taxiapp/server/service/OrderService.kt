package com.taxiapp.server.service

import com.taxiapp.server.dto.order.CreateOrderRequestDto
import com.taxiapp.server.dto.order.TaxiOrderDto
import com.taxiapp.server.model.enums.OrderStatus
import com.taxiapp.server.model.order.TaxiOrder
import com.taxiapp.server.model.order.OrderStop // <--- Імпорт
import com.taxiapp.server.model.user.Client
import com.taxiapp.server.model.user.Driver
import com.taxiapp.server.model.user.User
import com.taxiapp.server.repository.CarTariffRepository
import com.taxiapp.server.repository.TaxiOrderRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime

@Service
class OrderService(
    private val orderRepository: TaxiOrderRepository,
    private val tariffRepository: CarTariffRepository,
    private val promoService: PromoService
) {

    @Transactional
    fun createOrder(client: Client, request: CreateOrderRequestDto): TaxiOrderDto {
        
        val tariff = tariffRepository.findById(request.tariffId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Тариф не знайдено") }
            
        if (!tariff.isActive) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Тариф недоступний")
        }

        // Розрахунок знижки
        var finalPrice = request.price
        var discountAmount = 0.0
        
        val activeReward = promoService.findActiveReward(client)
        if (activeReward != null) {
            val percent = activeReward.promoTask.discountPercent
            discountAmount = finalPrice * (percent / 100.0)
            finalPrice -= discountAmount
            if (finalPrice < 0) finalPrice = 0.0
        }

        // 1. Створюємо об'єкт замовлення
        val newOrder = TaxiOrder(
            client = client,
            fromAddress = request.fromAddress,
            toAddress = request.toAddress,
            status = OrderStatus.REQUESTED,
            createdAt = LocalDateTime.now(),
            tariff = tariff, 
            price = finalPrice, 
            appliedDiscount = discountAmount,
            originLat = request.originLat,
            originLng = request.originLng,
            destLat = request.destLat,
            destLng = request.destLng,
            googleRoutePolyline = request.googleRoutePolyline
        )
        
        // 2. !!! ОБРОБКА ЗУПИНОК (Waypoints) !!!
        if (!request.waypoints.isNullOrEmpty()) {
            val stopsList = request.waypoints.mapIndexed { index, wpDto ->
                OrderStop(
                    address = wpDto.address,
                    lat = wpDto.lat,
                    lng = wpDto.lng,
                    stopOrder = index + 1, // Порядок: 1, 2, 3...
                    order = newOrder // Прив'язуємо до замовлення
                )
            }
            // Додаємо в список сутності
            newOrder.stops.addAll(stopsList)
        }
        
        // 3. Зберігаємо (Cascade збереже і зупинки)
        val savedOrder = orderRepository.save(newOrder)
        return TaxiOrderDto(savedOrder)
    }

    // ... (решта методів getOrderById, cancelOrder, completeOrder, acceptOrder без змін) ...
    // ... просто залиште їх як є в вашому файлі
    
    fun getOrderById(id: Long): TaxiOrderDto {
        val order = orderRepository.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено") }
        return TaxiOrderDto(order)
    }

    @Transactional
    fun cancelOrder(user: User, orderId: Long): TaxiOrderDto {
        val order = orderRepository.findById(orderId).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено") }
        if (order.client.id != user.id) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Чуже замовлення")
        if (order.status == OrderStatus.COMPLETED || order.status == OrderStatus.CANCELLED) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Замовлення вже закрите")
        order.status = OrderStatus.CANCELLED
        return TaxiOrderDto(orderRepository.save(order))
    }
    
    @Transactional
    fun completeOrder(driver: Driver, orderId: Long): TaxiOrderDto {
        val order = orderRepository.findById(orderId).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено") }
        if (order.driver?.id != driver.id) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Це не ваше замовлення")
        if (order.status != OrderStatus.ACCEPTED && order.status != OrderStatus.IN_PROGRESS) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Замовлення не в процесі")
        order.status = OrderStatus.COMPLETED
        order.completedAt = LocalDateTime.now()
        if (order.appliedDiscount > 0.0) promoService.markRewardAsUsed(order.client)
        promoService.updateProgressOnRideCompletion(order.client)
        return TaxiOrderDto(orderRepository.save(order))
    }

    @Transactional
    fun acceptOrder(driver: Driver, orderId: Long): TaxiOrderDto {
        val order = orderRepository.findById(orderId).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Замовлення не знайдено") }
        if (order.status != OrderStatus.REQUESTED) throw ResponseStatusException(HttpStatus.CONFLICT, "Замовлення вже не активне")
        order.driver = driver
        order.status = OrderStatus.ACCEPTED
        return TaxiOrderDto(orderRepository.save(order))
    }
}