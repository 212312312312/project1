package com.taxiapp.server.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.taxiapp.server.dto.auth.LoginResponse
import com.taxiapp.server.dto.auth.MessageResponse
import com.taxiapp.server.dto.auth.SmsRequestDto
import com.taxiapp.server.dto.driver.DriverDto
import com.taxiapp.server.dto.driver.UpdateDriverRequest
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
import org.springframework.web.multipart.MultipartHttpServletRequest
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// --- DTO КЛАССЫ ---

data class SosSignalDto(
    val driverId: Long,
    val driverName: String,
    val phone: String,
    val carNumber: String,
    val lat: Double,
    val lng: Double,
    val timestamp: String
)

data class ChangePhoneConfirmRequest(
    val newPhone: String,
    val code: String,
    val changeToken: String
)

data class CodeVerifyRequest(
    val code: String
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

    // --- НОВЫЙ МЕТОД ДЛЯ ОБНОВЛЕНИЯ ПРОФИЛЯ (ВКЛ. ИНВАЛИДНОСТЬ) ---
    @PatchMapping("/profile")
    fun updateProfile(
        @AuthenticationPrincipal userDetails: UserDetails,
        @RequestBody request: UpdateDriverRequest
    ): ResponseEntity<DriverDto> {
        val driver = getDriverFromUser(userDetails)
        val updatedDriverDto = driverService.updateProfile(driver, request)
        return ResponseEntity.ok(updatedDriverDto)
    }
    // -------------------------------------------------------------

    @PatchMapping("/status")
    fun updateStatus(
        @AuthenticationPrincipal userDetails: UserDetails,
        @Valid @RequestBody request: UpdateDriverStatusRequest
    ): ResponseEntity<DriverDto> {
        val driver = getDriverFromUser(userDetails)

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
        val driver = getDriverFromUser(userDetails)

        val sosDto = SosSignalDto(
            driverId = driver.id, 
            driverName = driver.fullName,
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
        val driver = getDriverFromUser(userDetails)
        return ResponseEntity.ok(DriverDto(driver))
    }

    // --- ЛОГИКА СМЕНЫ НОМЕРА ТЕЛЕФОНА ---

    @PostMapping("/profile/change-phone/request-current")
    fun requestCodeForCurrentPhone(@AuthenticationPrincipal userDetails: UserDetails): ResponseEntity<MessageResponse> {
        val driver = getDriverFromUser(userDetails)
        driverService.sendVerificationCodeToCurrentPhone(driver) 
        return ResponseEntity.ok(MessageResponse("Код відправлено на поточний номер"))
    }

    @PostMapping("/profile/change-phone/verify-current")
    fun verifyCurrentPhoneCode(
        @AuthenticationPrincipal userDetails: UserDetails,
        @RequestBody request: CodeVerifyRequest 
    ): ResponseEntity<Map<String, String>> {
        val driver = getDriverFromUser(userDetails)
        val changeToken = driverService.verifyCurrentPhoneCode(driver, request.code)
        return ResponseEntity.ok(mapOf("changeToken" to changeToken))
    }

    @PostMapping("/profile/change-phone/request-new")
    fun requestCodeForNewPhone(
        @RequestBody request: SmsRequestDto
    ): ResponseEntity<MessageResponse> {
        driverService.sendVerificationCodeToNewPhone(request.phoneNumber)
        return ResponseEntity.ok(MessageResponse("Код відправлено на новий номер"))
    }

    @PostMapping("/profile/change-phone/confirm-new")
    fun confirmNewPhone(
        @AuthenticationPrincipal userDetails: UserDetails,
        @RequestBody request: ChangePhoneConfirmRequest
    ): ResponseEntity<LoginResponse> {
        val driver = getDriverFromUser(userDetails)
        
        val updatedUser = driverService.changePhone(driver, request.newPhone, request.code, request.changeToken)
        
        val newToken = jwtUtils.generateToken(
            updatedUser, 
            updatedUser.id, 
            updatedUser.role.name
        )
        
        return ResponseEntity.ok(LoginResponse(
            token = newToken,
            role = updatedUser.role.name,
            userId = updatedUser.id,
            phoneNumber = updatedUser.userPhone ?: "",
            fullName = updatedUser.fullName ?: "",
            isNewUser = false
        ))
    }

    // --- ЛОГИКА ОБНОВЛЕНИЯ РНОКПП ---

    @PutMapping("/profile/rnokpp")
    fun updateRnokpp(
        @AuthenticationPrincipal userDetails: UserDetails,
        @RequestBody request: UpdateDriverRequest
    ): ResponseEntity<MessageResponse> {
        val driver = getDriverFromUser(userDetails)
        
        if (request.rnokpp == null || request.rnokpp.length != 10) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "РНОКПП повинен містити 10 цифр")
        }
        
        driverService.updateRnokpp(driver, request.rnokpp)
        return ResponseEntity.ok(MessageResponse("РНОКПП оновлено"))
    }

    // --- КАРТА И АВТОМОБИЛИ ---

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
        
        println("\n>>> ОТРИМАНО ЗАЯВКУ НА АВТО <<<")
        
        val driver = validateTokenAndGetDriver(token)

        val savedPhotos = mutableMapOf<String, String>()
        request.fileMap.forEach { (key, file) ->
            if (!file.isEmpty) {
                savedPhotos[key] = fileStorageService.storeFile(file)
            }
        }

        val node = ObjectMapper().readTree(carJson)
        
        fun txt(vararg keys: String): String {
            for (k in keys) {
                if (node.has(k)) return node.get(k).asText()
            }
            return "Unknown"
        }
        
        fun photo(key: String): String? = savedPhotos[key]

        val plate = txt("plate_number", "license_plate", "number", "gos_nomer")
        val make = txt("brand", "make")
        val model = txt("model")
        val color = txt("color")
        val vin = if (node.has("vin")) node.get("vin").asText() else "NO_VIN"
        
        val year = try {
            if (node.has("year")) node.get("year").asInt() else 2020
        } catch (e: Exception) { 2020 }

        val newCar = Car(
            driver = driver,
            make = make,
            model = model,
            plateNumber = plate,
            color = color,
            vin = vin, 
            year = year, 
            techPassportFront = photo("tech_passport_front"),
            techPassportBack  = photo("tech_passport_back"),
            insurancePhoto    = photo("insurance_photo"),
            photoFront = photo("photo_front"),
            photoBack  = photo("photo_back"),
            photoLeft  = photo("photo_left"),
            photoRight = photo("photo_right"),
            photoSeatsFront = photo("photo_seats_front"),
            photoSeatsBack  = photo("photo_seats_back"),
            photoUrl = photo("photo_front"), 
            status = CarStatus.PENDING 
        )
        
        carRepository.save(newCar)

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

        driver.car = car
        driverRepository.save(driver)

        return ResponseEntity.ok(mapOf("message" to "Активне авто змінено на ${car.make} ${car.model}"))
    }
    
    private fun getDriverFromUser(userDetails: UserDetails): Driver {
        val username = userDetails.username
        return (driverRepository.findByUserLogin(username) ?: driverRepository.findByUserPhone(username))
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Водій не знайдений")
    }
}