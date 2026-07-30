package com.taxiapp.server.controller

import com.taxiapp.server.dto.order.TaxiOrderDto
import com.taxiapp.server.repository.DriverRepository
import com.taxiapp.server.service.OrderService
import org.springframework.messaging.handler.annotation.DestinationVariable
import org.springframework.messaging.simp.annotation.SubscribeMapping
import org.springframework.stereotype.Controller

@Controller
class OrderWebSocketController(
    private val orderService: OrderService,
    private val driverRepository: DriverRepository
) {

    /**
     * Вызывается автоматически, когда Диспетчерская (React) подписывается на '/topic/admin/orders'
     * Возвращает начальный список активных заказов диспетчеру прямо в сокет.
     */
    @SubscribeMapping("/admin/orders")
    fun initAdminOrders(principal: java.security.Principal?): List<TaxiOrderDto> {
        if (principal == null) throw SecurityException("Unauthorized")
        val auth = principal as? org.springframework.security.core.Authentication
        
        // Только админ или диспетчер могут слушать этот канал
        if (auth == null || !auth.authorities.any { it.authority == "ROLE_ADMINISTRATOR" || it.authority == "ROLE_DISPATCHER" }) {
            throw SecurityException("Access Denied: Only dispatchers can view all orders")
        }
        return orderService.getActiveOrdersForDispatcher()
    }

    /**
     * Вызывается автоматически, когда Приложение Водителя подписывается на общий эфир '/topic/drivers/ether'
     * Возвращает водителю начальный список доступных заказов с учетом его фильтров.
     */
    @SubscribeMapping("/drivers/ether")
    fun initDriverEther(principal: java.security.Principal?): List<TaxiOrderDto> {
        if (principal == null) return emptyList() // Защита от анонимов
        val username = principal.name
        val driver = driverRepository.findByUserLogin(username)
            ?: driverRepository.findByUserPhone(username)
            ?: return emptyList()
            
        if (driver.isBlocked || driver.activityScore <= 0) return emptyList()
        return orderService.getFilteredOrdersForDriver(driver)
    }

    /**
     * Вызывается автоматически, когда Приложение Водителя подписывается на персональный топик '/topic/drivers/{driverId}/orders'
     * Возвращает водителю его текущий активный или предлагаемый заказ.
     */
    @SubscribeMapping("/drivers/{driverId}/orders")
    fun initDriverActiveOrder(@DestinationVariable driverId: Long, principal: java.security.Principal?): TaxiOrderDto? {
        if (principal == null) throw SecurityException("Unauthorized")
        
        val driver = driverRepository.findById(driverId).orElse(null) ?: return null
        
        // ЗАЩИТА: Водитель может слушать только свой личный топик!
        if (driver.userLogin != principal.name && driver.userPhone != principal.name) {
            throw SecurityException("Access Denied: You can only subscribe to your own orders")
        }
        
        return orderService.findActiveOrderByDriver(driver)
    }
}