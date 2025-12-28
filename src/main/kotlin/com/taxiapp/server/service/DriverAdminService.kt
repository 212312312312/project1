package com.taxiapp.server.service

import com.taxiapp.server.dto.auth.MessageResponse
import com.taxiapp.server.dto.auth.RegisterDriverRequest
import com.taxiapp.server.dto.driver.DriverDto
import com.taxiapp.server.dto.driver.TempBlockRequest
import com.taxiapp.server.dto.driver.UpdateDriverRequest
import com.taxiapp.server.model.enums.OrderStatus
import com.taxiapp.server.model.enums.Role
import com.taxiapp.server.model.user.Car
import com.taxiapp.server.model.user.Driver
import com.taxiapp.server.repository.CarTariffRepository
import com.taxiapp.server.repository.DriverRepository
import com.taxiapp.server.repository.TaxiOrderRepository
import com.taxiapp.server.repository.UserRepository
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime

@Service
class DriverAdminService(
    private val driverRepository: DriverRepository,
    private val taxiOrderRepository: TaxiOrderRepository,
    private val tariffRepository: CarTariffRepository,
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val fileStorageService: FileStorageService // Важно!
) {

    @Transactional(readOnly = true)
    fun getAllDrivers(): List<DriverDto> {
        return driverRepository.findAll()
            .sortedBy { it.id }
            .map { DriverDto(it) }
    }

    @Transactional
    fun createDriver(request: RegisterDriverRequest, file: MultipartFile?): MessageResponse {
        
        if (userRepository.existsByUserPhone(request.phoneNumber)) {
             throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Цей номер телефону вже зайнятий")
        }

        // Сохраняем файл
        val filename: String? = file?.let { fileStorageService.store(it) }

        val tariffs = tariffRepository.findAllById(request.tariffIds).toMutableSet()
        
        val car = Car(
            make = request.make,
            model = request.model,
            color = request.color,
            plateNumber = request.plateNumber,
            vin = request.vin,
            year = request.year
        )

        val driver = Driver().apply {
            this.userPhone = request.phoneNumber
            this.passwordHash = passwordEncoder.encode(request.password)
            this.fullName = request.fullName
            this.role = Role.DRIVER
            this.isBlocked = false
            this.isOnline = false
            this.car = car
            this.allowedTariffs = tariffs
            this.photoUrl = filename // Привязываем
        }

        driverRepository.save(driver)
        return MessageResponse("Водій успішно зареєстрований")
    }

    @Transactional
    fun updateDriver(driverId: Long, request: UpdateDriverRequest, file: MultipartFile?): DriverDto {
        val driver = driverRepository.findById(driverId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Водій з ID $driverId не знайдено") }

        // Обновляем фото
        if (file != null) {
            fileStorageService.delete(driver.photoUrl)
            driver.photoUrl = fileStorageService.store(file)
        }

        val tariffs = tariffRepository.findAllById(request.tariffIds).toMutableSet()

        driver.fullName = request.fullName
        driver.allowedTariffs = tariffs
        
        driver.car?.apply {
            make = request.make
            model = request.model
            color = request.color
            plateNumber = request.plateNumber
            vin = request.vin
            year = request.year
        }
        
        val updatedDriver = driverRepository.save(driver)
        return DriverDto(updatedDriver)
    }

    @Transactional
    fun deleteDriver(driverId: Long): MessageResponse {
        val driver = driverRepository.findById(driverId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Водій з ID $driverId не знайдено") }

        // Удаляем файл
        fileStorageService.delete(driver.photoUrl)

        val activeStatuses = listOf(OrderStatus.ACCEPTED, OrderStatus.IN_PROGRESS)
        val activeOrders = taxiOrderRepository.findAllByDriverAndStatusIn(driver, activeStatuses)

        activeOrders.forEach { order ->
            order.driver = null 
            order.status = OrderStatus.REQUESTED 
            taxiOrderRepository.save(order)
        }
        
        driver.allowedTariffs.clear()
        driverRepository.save(driver)
        
        driverRepository.delete(driver)
        
        return MessageResponse("Водій (ID $driverId) видалений.")
    }
    
    @Transactional
    fun blockDriverTemporarily(driverId: Long, request: TempBlockRequest): DriverDto {
        val driver = driverRepository.findById(driverId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Водій з ID $driverId не знайдено") }

        driver.tempBlockExpiresAt = LocalDateTime.now().plusHours(request.durationHours)
        driver.isBlocked = false 
        
        val updatedDriver = driverRepository.save(driver)
        return DriverDto(updatedDriver)
    }

    @Transactional
    fun blockDriverPermanently(driverId: Long): DriverDto {
        val driver = driverRepository.findById(driverId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Водій з ID $driverId не знайдено") }
        
        driver.isBlocked = true
        driver.tempBlockExpiresAt = null

        val updatedDriver = driverRepository.save(driver)
        return DriverDto(updatedDriver)
    }
    
    @Transactional
    fun unblockDriver(driverId: Long): DriverDto {
        val driver = driverRepository.findById(driverId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Водій з ID $driverId не знайдено") }

        driver.isBlocked = false
        driver.tempBlockExpiresAt = null

        val updatedDriver = driverRepository.save(driver)
        return DriverDto(updatedDriver)
    }
}