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
fun updateLocation(driverUuid: String, request: UpdateLocationRequest) {
    // 1. Ищем водителя по строковому UUID
    val driver = driverRepository.findByUuid(driverUuid).orElseThrow { RuntimeException("Driver not found") }
    val driverId = driver.id
    
    driver.latitude = request.lat
    driver.longitude = request.lng
    
    val newBearing = request.bearing ?: 0f
    driver.bearing = newBearing
    
    driverRepository.save(driver)

    // 2. Ретрансляция (Tracking) для активного заказа клиента
    val activeOrderOpt = orderRepository.findActiveOrderByDriverId(driverId)
    if (activeOrderOpt.isPresent) {
        val order = activeOrderOpt.get()
        val trackingDto = TrackingLocationDto(
            lat = request.lat,
            lng = request.lng,
            bearing = newBearing
        )
        messagingTemplate.convertAndSend("/topic/order/${order.uuid}/tracking", trackingDto)
    }

    // 3. ФИКС ДЛЯ ДИСПЕТЧЕРА: Трансляция координат на общую веб-карту в реальном времени!
    messagingTemplate.convertAndSend("/topic/admin/drivers/locations", DriverLocationDto(driver))
}

    @Transactional
    fun clearLocation(driverId: Long) {
        // Этот метод вызывается при logout/закрытии приложения - здесь очищаем полностью
        val driver = driverRepository.findById(driverId).orElse(null) ?: return
        driver.latitude = null
        driver.longitude = null
        if (driver.searchMode == DriverSearchMode.CHAIN || driver.searchMode == DriverSearchMode.HOME) {
            driver.searchMode = DriverSearchMode.MANUAL
        }
        driver.isOnline = false // На всякий случай дублируем
        driverRepository.save(driver)
        messagingTemplate.convertAndSend("/topic/admin/drivers/locations", DriverLocationDto(driver))
    }

    // НОВЫЙ МЕТОД: Получение 5 ближайших водителей (для сокетов клиента)
    fun getTop5NearestDrivers(lat: Double, lng: Double): List<DriverLocationDto> {
        val drivers = driverRepository.findTop5NearestAvailableDrivers(lat, lng)
        
        return drivers.map { driver ->
            DriverLocationDto(
                driverId = driver.id!!,
                fullName = driver.fullName ?: "Водій",
                lat = driver.latitude!!,
                lng = driver.longitude!!,
                bearing = driver.bearing ?: 0f,
                status = driver.searchMode.name,
                isOnline = driver.isOnline,
                carModel = driver.car?.model ?: "Не вказано",
                carColor = driver.car?.color ?: ""
            )
        }
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