package com.taxiapp.server.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.taxiapp.server.dto.driver.DriverDto
import com.taxiapp.server.dto.driver.UpdateDriverStatusRequest
import com.taxiapp.server.dto.driver.UpdateLocationRequest
import com.taxiapp.server.model.enums.CarStatus
import com.taxiapp.server.model.user.Car
import com.taxiapp.server.model.user.Driver
import com.taxiapp.server.repository.CarRepository
import com.taxiapp.server.repository.DriverRepository
import com.taxiapp.server.service.DriverLocationService
import com.taxiapp.server.service.DriverService
import com.taxiapp.server.service.DynamicFormService
import com.taxiapp.server.service.FileStorageService
import com.taxiapp.server.security.JwtUtils
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.multipart.MultipartHttpServletRequest
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// DTO для SOS сигналу
data class SosSignalDto(
    val driverId: Long,
    val driverName: String,
    val phone: String,
    val carNumber: String,
    val lat: Double,
    val lng: Double,
    val timestamp: String
)

@RestController
@RequestMapping("/api/v1/driver")
class DriverAppController(
    private val driverService: DriverService,
    private val driverLocationService: DriverLocationService,
    private val driverRepository: DriverRepository,
    private val jwtUtils: JwtUtils,
    private val dynamicFormService: DynamicFormService,
    private val fileStorageService: FileStorageService,
    private val carRepository: CarRepository
) {

    @Autowired
    private lateinit var messagingTemplate: SimpMessagingTemplate

    @PatchMapping("/status")
    fun updateStatus(
        @AuthenticationPrincipal userDetails: UserDetails,
        @Valid @RequestBody request: UpdateDriverStatusRequest
    ): ResponseEntity<DriverDto> {
        val username = userDetails.username
        val driver = (driverRepository.findByUserLogin(username) ?: driverRepository.findByUserPhone(username))
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Водій не знайдений")

        if (!driver.isAccountNonLocked) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Ваш акаунт заблокований")
        }

        val driverDto = driverService.updateDriverStatus(driver, request)
        return ResponseEntity.ok(driverDto)
    }

    @PostMapping("/location")
    fun updateLocation(
        @RequestBody request: UpdateLocationRequest,
        servletRequest: HttpServletRequest
    ): ResponseEntity<Void> {
        val authHeader = servletRequest.getHeader("Authorization")
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            val token = authHeader.substring(7)
            try {
                val driverId = jwtUtils.extractUserId(token)
                driverLocationService.updateLocation(driverId, request)
            } catch (e: Exception) {}
        }
        return ResponseEntity.ok().build()
    }

    @PostMapping("/sos")
    fun sendSosSignal(
        @RequestBody loc: UpdateLocationRequest, 
        @AuthenticationPrincipal userDetails: UserDetails
    ): ResponseEntity<String> {
        val username = userDetails.username
        val driver = (driverRepository.findByUserLogin(username) ?: driverRepository.findByUserPhone(username))
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Водій не знайдений")

        val sosDto = SosSignalDto(
            driverId = driver.id!!,
            driverName = driver.fullName ?: "Водій #${driver.id}",
            phone = driver.userPhone ?: "Не вказано", 
            carNumber = driver.car?.plateNumber ?: "Без авто",
            lat = loc.lat,
            lng = loc.lng,
            timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        )

        messagingTemplate.convertAndSend("/topic/admin/sos", sosDto)
        return ResponseEntity.ok("SOS Sent")
    }

    @GetMapping("/me")
    fun getDriverProfile(@AuthenticationPrincipal userDetails: UserDetails): ResponseEntity<DriverDto> {
        val username = userDetails.username
        val driver = (driverRepository.findByUserLogin(username) ?: driverRepository.findByUserPhone(username))
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Водія не знайдено")

        return ResponseEntity.ok(DriverDto(driver))
    }

    @DeleteMapping("/location")
    fun logoutFromMap(servletRequest: HttpServletRequest): ResponseEntity<Void> {
        val authHeader = servletRequest.getHeader("Authorization")
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            val token = authHeader.substring(7)
            val driverId = jwtUtils.extractUserId(token)
            driverLocationService.clearLocation(driverId)
        }
        return ResponseEntity.ok().build()
    }

    // --- ДИНАМИЧЕСКИЕ ФОРМЫ (УЛУЧШЕННАЯ ВЕРСИЯ) ---

    @GetMapping("/forms/add-car", produces = [MediaType.TEXT_HTML_VALUE])
    fun getAddCarForm(@RequestParam token: String): String {
        return dynamicFormService.generateHtmlForm("add_car", "/api/v1/driver/cars/add", token)
    }

    @PostMapping("/cars/add", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun addCar(
        @RequestParam("token") token: String,
        @RequestParam("data") carJson: String,
        request: MultipartHttpServletRequest
    ): ResponseEntity<Any> {
        
        println("\n>>> ПОЛУЧЕНА ПОЛНАЯ ЗАЯВКА НА АВТО <<<")
        
        val driver = validateTokenAndGetDriver(token)

        // 1. Сохраняем ВСЕ файлы, которые пришли
        val savedPhotos = mutableMapOf<String, String>()
        request.fileMap.forEach { (key, file) ->
            if (!file.isEmpty) {
                // key - это "tech_passport_front", "photo_left" и т.д.
                savedPhotos[key] = fileStorageService.storeFile(file)
            }
        }

        // 2. Парсим JSON
        val node = ObjectMapper().readTree(carJson)
        
        // Хелпер для поиска текста (ищет по нескольким ключам)
        fun txt(vararg keys: String): String {
            for (k in keys) {
                if (node.has(k)) return node.get(k).asText()
            }
            return "Unknown"
        }
        
        // Хелпер для поиска фото (берет сохраненный путь)
        fun photo(key: String): String? = savedPhotos[key]

        // 3. Собираем данные
        val plate = txt("plate_number", "license_plate", "number", "gos_nomer")
        val make = txt("brand", "make")
        val model = txt("model")
        val color = txt("color")
        val vin = if (node.has("vin")) node.get("vin").asText() else "NO_VIN"
        
        // Год выпуска (парсим в Int, если ошибка - 2020)
        val year = try {
            if (node.has("year")) node.get("year").asInt() else 2020
        } catch (e: Exception) { 2020 }

        // 4. Создаем машину со ВСЕМИ полями
        val newCar = Car(
            driver = driver,
            make = make,
            model = model,
            plateNumber = plate,
            color = color,
            vin = vin, 
            year = year, 
            
            // --- ДОКУМЕНТЫ ---
            techPassportFront = photo("tech_passport_front"),
            techPassportBack  = photo("tech_passport_back"),
            insurancePhoto    = photo("insurance_photo"),

            // --- ЭКСТЕРЬЕР ---
            photoFront = photo("photo_front"), // Главное фото
            photoBack  = photo("photo_back"),
            photoLeft  = photo("photo_left"),
            photoRight = photo("photo_right"),

            // --- ИНТЕРЬЕР ---
            photoSeatsFront = photo("photo_seats_front"),
            photoSeatsBack  = photo("photo_seats_back"),
            
            // Главное фото для списка (используем фото спереди)
            photoUrl = photo("photo_front"), 
            
            status = CarStatus.PENDING 
        )
        
        carRepository.save(newCar)

        println("Авто сохранено: $make $model ($plate). Фотографий загружено: ${savedPhotos.size}")

        return ResponseEntity.ok("""
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    body { font-family: sans-serif; display: flex; flex-direction: column; align-items: center; justify-content: center; height: 100vh; margin: 0; background: #f0fdf4; }
                    h1 { color: #16a34a; margin-bottom: 10px; }
                    p { color: #333; font-size: 18px; text-align: center; }
                    button { margin-top: 30px; padding: 15px 30px; background: #16a34a; color: white; border: none; border-radius: 8px; font-size: 16px; font-weight: bold; }
                </style>
            </head>
            <body>
                <div style="font-size: 60px;">✅</div>
                <h1>Заявка прийнята!</h1>
                <p>Всі документи та фото отримано.</p>
                <p><b>$make $model</b> відправлено на перевірку.</p>
                <button onclick="history.back()">Повернутися назад</button>
            </body>
            </html>
        """.trimIndent())
    }

    private fun validateTokenAndGetDriver(token: String): Driver {
        try {
            val driverId = jwtUtils.extractUserId(token)
            return driverRepository.findById(driverId)
                .orElseThrow { RuntimeException("Driver not found") }
        } catch (e: Exception) {
             throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token")
        }
    }

    @GetMapping("/cars")
    fun getMyCars(@AuthenticationPrincipal userDetails: UserDetails): ResponseEntity<List<Car>> {
        val driver = getDriverFromUser(userDetails)
        val cars = carRepository.findAllByDriver(driver)
        return ResponseEntity.ok(cars)
    }

    // Сделать машину активной (выбрать, на какой я работаю сейчас)
    @PostMapping("/cars/{carId}/select")
    fun selectActiveCar(
        @AuthenticationPrincipal userDetails: UserDetails,
        @PathVariable carId: Long
    ): ResponseEntity<Any> {
        val driver = getDriverFromUser(userDetails)
        val car = carRepository.findById(carId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Авто не знайдено") }

        if (car.driver?.id != driver.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Це не ваше авто")
        }
        if (car.status != CarStatus.ACTIVE) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Це авто ще не перевірено або заблоковано")
        }

        // Обновляем текущую машину водителя
        driver.car = car
        driverRepository.save(driver)

        return ResponseEntity.ok(mapOf("message" to "Активне авто змінено на ${car.make} ${car.model}"))
    }
    
    // Вспомогательный метод (чтобы не дублировать код поиска)
    private fun getDriverFromUser(userDetails: UserDetails): Driver {
        val username = userDetails.username
        return (driverRepository.findByUserLogin(username) ?: driverRepository.findByUserPhone(username))
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Водій не знайдений")
    }
}