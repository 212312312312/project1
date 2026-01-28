package com.taxiapp.server.service

import com.taxiapp.server.dto.driver.DriverLocationDto
import com.taxiapp.server.dto.driver.UpdateLocationRequest
import com.taxiapp.server.dto.order.TrackingLocationDto
import com.taxiapp.server.model.enums.DriverSearchMode
import com.taxiapp.server.repository.DriverRepository
import com.taxiapp.server.repository.TaxiOrderRepository
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class DriverLocationService(
    private val driverRepository: DriverRepository,
    private val orderRepository: TaxiOrderRepository,
    private val messagingTemplate: SimpMessagingTemplate
) {

    @Transactional
    fun updateLocation(driverId: Long, request: UpdateLocationRequest) {
        val driver = driverRepository.findById(driverId).orElseThrow { RuntimeException("Driver not found") }
        
        driver.latitude = request.lat
        driver.longitude = request.lng
        
        val newBearing = request.bearing ?: 0f
        driver.bearing = newBearing
        
        driverRepository.save(driver)

        // Ретрансляция (Tracking)
        val activeOrderOpt = orderRepository.findActiveOrderByDriverId(driverId)
        
        if (activeOrderOpt.isPresent) {
            val order = activeOrderOpt.get()
            
            val trackingDto = TrackingLocationDto(
                lat = request.lat,
                lng = request.lng,
                bearing = newBearing
            )

            messagingTemplate.convertAndSend("/topic/order/${order.id}/tracking", trackingDto)
        }
    }

    @Transactional
    fun clearLocation(driverId: Long) {
        // Этот метод вызывается при logout/закрытии приложения - здесь очищаем полностью
        val driver = driverRepository.findById(driverId).orElse(null) ?: return
        driver.latitude = null
        driver.longitude = null
        driver.searchMode = DriverSearchMode.OFFLINE
        driver.isOnline = false // На всякий случай дублируем
        driverRepository.save(driver)
    }

    fun getOnlineDriversForMap(): List<DriverLocationDto> {
        // ИСПРАВЛЕНО: Убрали фильтр "&& it.searchMode != OFFLINE".
        // Теперь показываем всех, у кого есть координаты.
        val drivers = driverRepository.findAll().filter { 
            it.latitude != null && it.longitude != null 
        }
        
        return drivers.map { driver ->
            DriverLocationDto(
                driverId = driver.id!!,
                fullName = driver.fullName ?: "Водій",
                lat = driver.latitude!!,
                lng = driver.longitude!!,
                bearing = driver.bearing ?: 0f,
                status = driver.searchMode.name,
                isOnline = driver.isOnline, // Важно передать это для React (зеленый/серый)
                carModel = driver.car?.model ?: "Не вказано",
                carColor = driver.car?.color ?: ""
            )
        }
    }
}