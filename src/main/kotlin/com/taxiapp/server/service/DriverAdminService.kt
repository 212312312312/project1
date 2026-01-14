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
    private val fileStorageService: FileStorageService,
    // Додали DriverActivityService для роботи з балами
    private val driverActivityService: DriverActivityService 
) {

    @Transactional(readOnly = true)
    fun getAllDrivers(): List<DriverDto> {
        return driverRepository.findAll()
            .sortedBy { it.id }
            .map { DriverDto(it) }
    }

    @Transactional
    fun createDriver(
        request: RegisterDriverRequest, 
        file: MultipartFile?, 
        carFiles: Map<String, MultipartFile>
    ): MessageResponse {
        
        if (userRepository.existsByUserPhone(request.phoneNumber)) {
             throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Цей номер телефону вже зайнятий")
        }

        val filename: String? = file?.let { fileStorageService.store(it) }

        val tariffs = tariffRepository.findAllById(request.tariffIds).toMutableSet()
        
        val car = Car(
            make = request.make,
            model = request.model,
            color = request.color,
            plateNumber = request.plateNumber,
            vin = request.vin,
            year = request.year,
            carType = request.carType
        )
        
        saveCarPhotos(car, carFiles)

        val driver = Driver().apply {
            this.userPhone = request.phoneNumber
            this.passwordHash = passwordEncoder.encode(request.password)
            this.fullName = request.fullName
            
            this.email = request.email
            this.rnokpp = request.rnokpp
            this.driverLicense = request.driverLicense
            
            this.role = Role.DRIVER
            this.isBlocked = false
            this.isOnline = false
            this.car = car
            this.allowedTariffs = tariffs
            this.photoUrl = filename
            
            // За замовчуванням
            this.activityScore = 1000
        }

        driverRepository.save(driver)
        return MessageResponse("Водій успішно зареєстрований")
    }

    @Transactional
    fun updateDriver(
        driverId: Long, 
        request: UpdateDriverRequest, 
        file: MultipartFile?, 
        carFiles: Map<String, MultipartFile>
    ): DriverDto {
        val driver = driverRepository.findById(driverId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Водій з ID $driverId не знайдено") }

        if (file != null && !file.isEmpty) {
            fileStorageService.delete(driver.photoUrl)
            driver.photoUrl = fileStorageService.store(file)
        }
        
        if (driver.car != null) {
            saveCarPhotos(driver.car!!, carFiles)
        }

        val tariffs = tariffRepository.findAllById(request.tariffIds).toMutableSet()

        driver.fullName = request.fullName
        driver.email = request.email
        driver.rnokpp = request.rnokpp
        driver.driverLicense = request.driverLicense
        driver.allowedTariffs = tariffs
        
        driver.car?.apply {
            make = request.make
            model = request.model
            color = request.color
            plateNumber = request.plateNumber
            vin = request.vin
            year = request.year
            if (request.carType != null) {
                carType = request.carType
            }
        }
        
        val updatedDriver = driverRepository.save(driver)
        return DriverDto(updatedDriver)
    }

    private fun saveCarPhotos(car: Car, files: Map<String, MultipartFile>) {
        val mainPhoto = files["carPhoto"] ?: files["carFile"]
        mainPhoto?.let { if (!it.isEmpty) { 
            fileStorageService.delete(car.photoUrl)
            car.photoUrl = fileStorageService.store(it) 
        }}

        files["techPassportFront"]?.let { if (!it.isEmpty) {
            fileStorageService.delete(car.techPassportFront)
            car.techPassportFront = fileStorageService.store(it)
        }}
        files["techPassportBack"]?.let { if (!it.isEmpty) {
            fileStorageService.delete(car.techPassportBack)
            car.techPassportBack = fileStorageService.store(it)
        }}
        
        files["insurancePhoto"]?.let { if (!it.isEmpty) {
            fileStorageService.delete(car.insurancePhoto)
            car.insurancePhoto = fileStorageService.store(it)
        }}

        files["photoFront"]?.let { if (!it.isEmpty) { 
            fileStorageService.delete(car.photoFront)
            car.photoFront = fileStorageService.store(it) 
        }}
        files["photoBack"]?.let { if (!it.isEmpty) { 
            fileStorageService.delete(car.photoBack)
            car.photoBack = fileStorageService.store(it) 
        }}
        files["photoLeft"]?.let { if (!it.isEmpty) { 
            fileStorageService.delete(car.photoLeft)
            car.photoLeft = fileStorageService.store(it) 
        }}
        files["photoRight"]?.let { if (!it.isEmpty) { 
            fileStorageService.delete(car.photoRight)
            car.photoRight = fileStorageService.store(it) 
        }}
        files["photoSeatsFront"]?.let { if (!it.isEmpty) { 
            fileStorageService.delete(car.photoSeatsFront)
            car.photoSeatsFront = fileStorageService.store(it) 
        }}
        files["photoSeatsBack"]?.let { if (!it.isEmpty) { 
            fileStorageService.delete(car.photoSeatsBack)
            car.photoSeatsBack = fileStorageService.store(it) 
        }}
    }

    @Transactional
    fun deleteDriver(driverId: Long): MessageResponse {
        val driver = driverRepository.findById(driverId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Водій з ID $driverId не знайдено") }

        fileStorageService.delete(driver.photoUrl)
        
        driver.car?.let { car ->
            fileStorageService.delete(car.photoUrl)
            fileStorageService.delete(car.techPassportFront)
            fileStorageService.delete(car.techPassportBack)
            fileStorageService.delete(car.insurancePhoto)
            fileStorageService.delete(car.photoFront)
            fileStorageService.delete(car.photoBack)
            fileStorageService.delete(car.photoLeft)
            fileStorageService.delete(car.photoRight)
            fileStorageService.delete(car.photoSeatsFront)
            fileStorageService.delete(car.photoSeatsBack)
        }

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

    // --- НОВИЙ МЕТОД ЗМІНИ АКТИВНОСТІ ---
    @Transactional
    fun updateDriverActivity(driverId: Long, points: Int, reason: String): DriverDto {
        val driver = driverRepository.findById(driverId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Водій з ID $driverId не знайдено") }
        
        // Викликаємо метод з DriverActivityService 
        // (Переконайся, що метод updateScore в DriverActivityService є public/відкритим!)
        // Якщо він private, зміни його на public або додай метод manualUpdate в DriverActivityService.
        driverActivityService.updateScore(driver, points, reason)

        return DriverDto(driver)
    }
}