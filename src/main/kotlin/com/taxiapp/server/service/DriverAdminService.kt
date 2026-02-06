package com.taxiapp.server.service

import com.taxiapp.server.dto.auth.MessageResponse
import com.taxiapp.server.dto.auth.RegisterDriverRequest
import com.taxiapp.server.dto.driver.DriverDto
import com.taxiapp.server.dto.driver.TempBlockRequest
import com.taxiapp.server.dto.driver.UpdateDriverRequest
import com.taxiapp.server.model.enums.OrderStatus
import com.taxiapp.server.model.enums.RegistrationStatus
import com.taxiapp.server.model.enums.Role
import com.taxiapp.server.model.user.Car
import com.taxiapp.server.model.user.Driver
import com.taxiapp.server.repository.CarRepository
import com.taxiapp.server.repository.CarTariffRepository
import com.taxiapp.server.repository.DriverRepository
import com.taxiapp.server.repository.TaxiOrderRepository
import com.taxiapp.server.repository.UserRepository
import org.springframework.http.HttpStatus
import org.springframework.messaging.simp.SimpMessagingTemplate
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
    private val driverActivityService: DriverActivityService,
    private val carRepository: CarRepository,
    private val messagingTemplate: SimpMessagingTemplate
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

        // 1. Создаем водителя
        val filename: String? = file?.let { fileStorageService.storeFile(it) }
        val tariffs = tariffRepository.findAllById(request.tariffIds).toMutableSet()

        var driver = Driver().apply {
            this.userPhone = request.phoneNumber
            this.passwordHash = passwordEncoder.encode(request.password)
            this.fullName = request.fullName
            this.email = request.email
            this.rnokpp = request.rnokpp
            this.driverLicense = request.driverLicense
            this.role = Role.DRIVER
            this.isBlocked = false
            this.isOnline = false
            this.allowedTariffs = tariffs
            this.photoUrl = filename
            this.activityScore = 1000
        }
        
        driver = driverRepository.save(driver)

        // 2. Создаем авто
        val car = Car(
            driver = driver,
            make = request.make,
            model = request.model,
            color = request.color,
            plateNumber = request.plateNumber,
            vin = "", // VIN при регистрации больше не обязателен
            year = request.year,
            carType = request.carType,
            status = com.taxiapp.server.model.enums.CarStatus.ACTIVE
        )
        
        saveCarPhotos(car, carFiles)
        
        val savedCar = carRepository.save(car)

        // 3. Делаем авто активным
        driver.car = savedCar
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
            driver.photoUrl = fileStorageService.storeFile(file)
        }
        
        val driverCar = driver.car
        if (driverCar != null) {
            saveCarPhotos(driverCar, carFiles)
            
            // ИСПРАВЛЕНО: Если в запросе null, оставляем старое значение
            request.make?.let { driverCar.make = it }
            request.model?.let { driverCar.model = it }
            request.color?.let { driverCar.color = it }
            request.plateNumber?.let { driverCar.plateNumber = it }
            request.year?.let { driverCar.year = it }
            
            if (request.carType != null) {
                driverCar.carType = request.carType
            }
        }

        // Обновляем тарифы только если они переданы
        if (request.tariffIds.isNotEmpty()) {
            val tariffs = tariffRepository.findAllById(request.tariffIds).toMutableSet()
            driver.allowedTariffs = tariffs
        }

        // Обновляем поля водителя (тоже с проверкой на null)
        request.fullName?.let { driver.fullName = it }
        request.email?.let { driver.email = it }
        request.rnokpp?.let { driver.rnokpp = it }
        request.driverLicense?.let { driver.driverLicense = it }
        
        val updatedDriver = driverRepository.save(driver)
        return DriverDto(updatedDriver)
    }

    @Transactional(readOnly = true)
    fun getPendingCars(): List<Car> {
        return carRepository.findAll().filter { it.status == com.taxiapp.server.model.enums.CarStatus.PENDING }
    }

    @Transactional
    fun approveCar(carId: Long): MessageResponse {
        val car = carRepository.findById(carId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Авто не знайдено") }
        
        car.status = com.taxiapp.server.model.enums.CarStatus.ACTIVE
        car.rejectionReason = null 
        
        val driver = car.driver!!
        
        // Если у водителя нет активного авто, назначаем это
        if (driver.car == null) {
            driver.car = car
            driverRepository.save(driver)
        }

        carRepository.save(car)

        val notification = mapOf(
            "type" to "CAR_APPROVED",
            "message" to "Ваше авто ${car.make} ${car.model} схвалено! Тепер воно доступне у списку.",
            "carId" to car.id
        )
        messagingTemplate.convertAndSend("/topic/driver/${driver.id}", notification)
        
        return MessageResponse("Авто успішно схвалено!")
    }

    @Transactional
    fun rejectCar(carId: Long, reason: String): MessageResponse {
        val car = carRepository.findById(carId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Авто не знайдено") }
        
        car.status = com.taxiapp.server.model.enums.CarStatus.REJECTED
        car.rejectionReason = reason
        carRepository.save(car)
        
        return MessageResponse("Авто відхилено. Причина записана.")
    }

    private fun saveCarPhotos(car: Car, files: Map<String, MultipartFile>) {
        val mainPhoto = files["carPhoto"] ?: files["carFile"]
        
        if (mainPhoto != null && !mainPhoto.isEmpty) {
            fileStorageService.delete(car.photoUrl)
            car.photoUrl = fileStorageService.storeFile(mainPhoto)
        }

        files["techPassportFront"]?.let { 
            if (!it.isEmpty) {
                fileStorageService.delete(car.techPassportFront)
                car.techPassportFront = fileStorageService.storeFile(it)
            }
        }
        files["techPassportBack"]?.let { 
            if (!it.isEmpty) {
                fileStorageService.delete(car.techPassportBack)
                car.techPassportBack = fileStorageService.storeFile(it)
            }
        }
        files["insurancePhoto"]?.let { 
            if (!it.isEmpty) {
                fileStorageService.delete(car.insurancePhoto)
                car.insurancePhoto = fileStorageService.storeFile(it)
            }
        }
        files["photoFront"]?.let { 
            if (!it.isEmpty) {
                fileStorageService.delete(car.photoFront)
                car.photoFront = fileStorageService.storeFile(it)
            }
        }
        files["photoBack"]?.let { 
            if (!it.isEmpty) {
                fileStorageService.delete(car.photoBack)
                car.photoBack = fileStorageService.storeFile(it)
            }
        }
        files["photoLeft"]?.let { 
            if (!it.isEmpty) {
                fileStorageService.delete(car.photoLeft)
                car.photoLeft = fileStorageService.storeFile(it)
            }
        }
        files["photoRight"]?.let { 
            if (!it.isEmpty) {
                fileStorageService.delete(car.photoRight)
                car.photoRight = fileStorageService.storeFile(it)
            }
        }
        files["photoSeatsFront"]?.let { 
            if (!it.isEmpty) {
                fileStorageService.delete(car.photoSeatsFront)
                car.photoSeatsFront = fileStorageService.storeFile(it)
            }
        }
        files["photoSeatsBack"]?.let { 
            if (!it.isEmpty) {
                fileStorageService.delete(car.photoSeatsBack)
                car.photoSeatsBack = fileStorageService.storeFile(it)
            }
        }
    }

    @Transactional
    fun deleteDriver(driverId: Long): MessageResponse {
        val driver = driverRepository.findById(driverId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Водій з ID $driverId не знайдено") }

        fileStorageService.delete(driver.photoUrl)
        
        val allDriverCars = carRepository.findAllByDriver(driver)
        
        allDriverCars.forEach { car ->
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
            
            if (driver.car?.id == car.id) {
                driver.car = null
            }
            
            carRepository.delete(car)
        }
        
        driverRepository.save(driver)

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
        
        return MessageResponse("Водій (ID $driverId) та всі його авто видалені.")
    }

    @Transactional
    fun updateCarDetails(carId: Long, request: com.taxiapp.server.dto.driver.CarDto): com.taxiapp.server.model.user.Car {
        val car = carRepository.findById(carId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Авто не знайдено") }

        car.make = request.make
        car.model = request.model
        car.plateNumber = request.plateNumber
        car.color = request.color
        car.year = request.year
        
        car.vin = request.vin ?: "" // Если null, ставим пустую строку
        car.carType = request.carType ?: "Standard"

        return carRepository.save(car)
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

    @Transactional
    fun updateDriverActivity(driverId: Long, points: Int, reason: String): DriverDto {
        val driver = driverRepository.findById(driverId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Водій з ID $driverId не знайдено") }
        
        driverActivityService.updateScore(driver, points, reason)

        return DriverDto(driver)
    }

    @Transactional
    fun approveDriverRegistration(driverId: Long, tariffIds: List<Long>) {
        val driver = driverRepository.findById(driverId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Водія не знайдено") }

        val tariffs = tariffRepository.findAllById(tariffIds).toMutableSet()
        
        if (tariffs.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Необхідно обрати хоча б один тариф!")
        }

        driver.registrationStatus = RegistrationStatus.APPROVED
        driver.car?.status = com.taxiapp.server.model.enums.CarStatus.ACTIVE
        driver.allowedTariffs = tariffs

        driverRepository.save(driver)
    }

    @Transactional
    fun rejectDriverRegistration(driverId: Long, reason: String) {
        val driver = driverRepository.findById(driverId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Водія не знайдено") }
        
        driver.registrationStatus = RegistrationStatus.REJECTED
        driverRepository.save(driver)
    }
}