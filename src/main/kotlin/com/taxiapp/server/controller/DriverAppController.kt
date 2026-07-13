package com.taxiapp.server.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.taxiapp.server.dto.auth.LoginResponse
import com.taxiapp.server.dto.auth.MessageResponse
import com.taxiapp.server.dto.auth.SmsRequestDto
import com.taxiapp.server.dto.driver.DriverDto
import com.taxiapp.server.dto.driver.UpdateDriverRequest
import com.taxiapp.server.dto.driver.UpdateDriverStatusRequest
import com.taxiapp.server.dto.driver.UpdateLocationRequest
import com.taxiapp.server.dto.driver.CarDto // ИСПРАВЛЕНО: Добавлен импорт для CarDto
import com.taxiapp.server.model.enums.CarStatus
import com.taxiapp.server.model.user.Car
import com.taxiapp.server.model.user.Driver
import com.taxiapp.server.model.user.User
import com.taxiapp.server.repository.CarRepository
import org.springframework.security.access.prepost.PreAuthorize
import com.taxiapp.server.repository.DriverRepository
import com.taxiapp.server.service.DriverLocationService
import com.taxiapp.server.service.DriverService
import com.taxiapp.server.service.FileStorageService
import com.taxiapp.server.service.SettingsService
import com.taxiapp.server.security.JwtUtils
import jakarta.servlet.http.HttpServletRequest
import com.taxiapp.server.service.LiqPayService
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
import com.taxiapp.server.repository.DriverNotificationRepository
import com.taxiapp.server.dto.driver.DriverNotificationDto

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

data class WalletTransactionDto(
    val id: Long,
    val amount: Double,
    val operationType: String,
    val description: String?,
    val createdAt: String,
    val balanceAfter: Double, // ДОБАВЛЕНО: Остаток баланса после операции
    val orderId: Long?        // ДОБАВЛЕНО: ID заказа для клика и перехода в детали
)

// ДТО для карт выплат водителя
data class DriverCardDto(
    val id: Long,
    val cardNumber: String,
    val cardHolder: String?,
    val isMain: Boolean
)

data class AddCardRequest(
    val cardNumber: String,
    val cardHolder: String?
)

@RestController
@RequestMapping("/api/v1/driver")
@PreAuthorize("hasAuthority('ROLE_DRIVER')")
class DriverAppController(
    private val driverService: DriverService,
    private val driverLocationService: DriverLocationService,
    private val driverRepository: DriverRepository,
    private val jwtUtils: JwtUtils,
    private val fileStorageService: FileStorageService,
    private val carRepository: CarRepository,
    private val settingsService: SettingsService,
    private val notificationRepository: DriverNotificationRepository,
    private val authService: com.taxiapp.server.service.AuthService,
    private val liqPayService: LiqPayService
) {

    @Autowired
    private lateinit var messagingTemplate: SimpMessagingTemplate

    @GetMapping("/notifications")
    fun getNotifications(@AuthenticationPrincipal userDetails: UserDetails): ResponseEntity<List<DriverNotificationDto>> {
        val driver = getDriverFromUser(userDetails)
        val notifications = notificationRepository.findAllByDriverIdOrderByCreatedAtDesc(driver.id!!)
        val dtos = notifications.map { n ->
            DriverNotificationDto(
                id = n.id,
                title = n.title,
                body = n.body,
                type = n.type,
                date = n.createdAt.format(DateTimeFormatter.ofPattern("dd.MM HH:mm")),
                isRead = n.isRead
            )
        }
        return ResponseEntity.ok(dtos)
    }

    @PatchMapping("/profile")
    fun updateProfile(
        @AuthenticationPrincipal userDetails: UserDetails,
        @RequestBody request: UpdateDriverRequest
    ): ResponseEntity<DriverDto> {
        val driver = getDriverFromUser(userDetails)
        val updatedDriverDto = driverService.updateProfile(driver, request)
        return ResponseEntity.ok(updatedDriverDto)
    }

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

    @PutMapping("/profile/tariffs")
    fun updateSelectedTariffs(
        @AuthenticationPrincipal userDetails: UserDetails,
        @RequestBody selectedIds: Set<Long>
    ): ResponseEntity<DriverDto> {
        val driver = getDriverFromUser(userDetails)
        if (!driver.isAccountNonLocked) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Ваш акаунт заблокований")
        }
        
        val updatedDriverDto = driverService.updateSelectedTariffs(driver.id!!, selectedIds)
        return ResponseEntity.ok(updatedDriverDto)
    }

    @PostMapping("/location")
fun updateLocation(
    @AuthenticationPrincipal user: User,
    @RequestBody request: UpdateLocationRequest
): ResponseEntity<Void> {
    if (user !is Driver) throw ResponseStatusException(HttpStatus.FORBIDDEN)
    
    try {
        driverLocationService.updateLocation(user.uuid, request)
    } catch (e: Exception) {
        println(">>> ОШИБКА ОБНОВЛЕНИЯ ЛОКАЦИИ ВОДИТЕЛЯ ${user.id}: ${e.message}")
    }
    return ResponseEntity.ok().build()
}

    // ПОЛНОСТЬЮ ЗАМЕНИ ЭТИ ДВА МЕТОДА В DriverAppController.kt

    @GetMapping("/transactions")
    fun getTransactions(@AuthenticationPrincipal user: User): ResponseEntity<List<WalletTransactionDto>> {
        if (user !is Driver) throw ResponseStatusException(HttpStatus.FORBIDDEN)
        val transactions = driverService.getDriverTransactions(user)
        
        val dtos = transactions.map { tx ->
            WalletTransactionDto(
                id = tx.id ?: 0L,
                amount = tx.amount,
                operationType = tx.operationType.name,
                description = tx.description,
                createdAt = tx.createdAt.toString(),
                balanceAfter = tx.balanceAfter,
                orderId = tx.orderId // 👈 Напрямую читаем заполненное поле из БД
            )
        }
        return ResponseEntity.ok(dtos)
    }

    @GetMapping("/transactions/pending")
    fun getPendingTransactions(@AuthenticationPrincipal user: User): ResponseEntity<List<WalletTransactionDto>> {
        if (user !is Driver) throw ResponseStatusException(HttpStatus.FORBIDDEN)
        val transactions = driverService.getPendingDriverTransactions(user)
        
        val dtos = transactions.map { tx ->
            WalletTransactionDto(
                id = tx.id ?: 0L,
                amount = tx.amount,
                operationType = tx.operationType.name,
                description = tx.description,
                createdAt = tx.createdAt.toString(),
                balanceAfter = tx.balanceAfter,
                orderId = tx.orderId // 👈 Напрямую читаем заполненное поле из БД
            )
        }
        return ResponseEntity.ok(dtos)
    }

   

    // --- НОВЫЙ ЭНДПОИНТ: СОХРАНЕНИЕ АКТУАЛЬНОГО FCM ТОКЕНА ВОДИТЕЛЯ ---
    @PostMapping("/profile/fcm-token")
    fun updateFcmToken(
        @AuthenticationPrincipal userDetails: UserDetails,
        @RequestBody request: Map<String, String>
    ): ResponseEntity<MessageResponse> {
        val driver = getDriverFromUser(userDetails)
        val token = request["token"]
        
        if (token.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Токен не може бути порожнім")
        }

        driver.fcmToken = token
        driverRepository.save(driver)

        return ResponseEntity.ok(MessageResponse("FCM токен водія успішно оновлено"))
    }

    // --- ЭНДПОИНТЫ КАРТ ВЫПЛАТ ---
    @GetMapping("/cards")
    fun getCards(@AuthenticationPrincipal user: User): ResponseEntity<List<DriverCardDto>> {
        if (user !is Driver) throw ResponseStatusException(HttpStatus.FORBIDDEN)
        val cards = driverService.getDriverCards(user.id!!)
        val dtos = cards.map { DriverCardDto(it.id!!, it.cardNumber, it.cardHolder, it.isMain) }
        return ResponseEntity.ok(dtos)
    }

    // Заменяем на эндпоинт инициализации привязки через LiqPay
    @PostMapping("/cards/init")
    fun initAddCard(@AuthenticationPrincipal user: User): ResponseEntity<Map<String, String>> {
        if (user !is Driver) throw ResponseStatusException(HttpStatus.FORBIDDEN)
        
        // Генерируем безопасную веб-ссылку на форму LiqPay для нашего WebView
        val url = liqPayService.generateDriverBindCardUrl(user.id!!)
        return ResponseEntity.ok(mapOf("url" to url))
    }

    @DeleteMapping("/cards/{cardId}")
    fun deleteCard(@AuthenticationPrincipal user: User, @PathVariable cardId: Long): ResponseEntity<MessageResponse> {
        if (user !is Driver) throw ResponseStatusException(HttpStatus.FORBIDDEN)
        driverService.deleteDriverCard(user.id!!, cardId)
        return ResponseEntity.ok(MessageResponse("Картку видалено успішно"))
    }

    @PostMapping("/cards/{cardId}/select")
    fun selectMainCard(@AuthenticationPrincipal user: User, @PathVariable cardId: Long): ResponseEntity<MessageResponse> {
        if (user !is Driver) throw ResponseStatusException(HttpStatus.FORBIDDEN)
        driverService.selectMainCard(user.id!!, cardId)
        return ResponseEntity.ok(MessageResponse("Основну картку змінено"))
    }

    @GetMapping("/commission")
    fun getCommissionInfo(): ResponseEntity<Map<String, Any>> {
        val percent = settingsService.getDriverCommissionPercent()
        return ResponseEntity.ok(mapOf(
            "percent" to percent,
            "description" to "Комісія сервісу стягується автоматично після завершення кожного замовлення."
        ))
    }

    // 3. Замени метод sendSosSignal на чистый:
@PostMapping("/sos")
fun sendSosSignal(
    @RequestBody loc: UpdateLocationRequest, 
    @AuthenticationPrincipal userDetails: UserDetails
): ResponseEntity<String> {
    val driver = getDriverFromUser(userDetails)
    val verifiedLocation = driverLocationService.getDriverLocation(driver.id!!) ?: loc
    
    val sosDto = SosSignalDto(
        driverId = driver.id!!, 
        driverName = driver.fullName ?: "Водій",
        phone = driver.userPhone ?: "Не вказано", 
        carNumber = driver.car?.plateNumber ?: "Без авто",
        lat = verifiedLocation.lat, 
        lng = verifiedLocation.lng, 
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
    fun requestCodeForNewPhone(@RequestBody request: SmsRequestDto): ResponseEntity<MessageResponse> {
        driverService.sendVerificationCodeToNewPhone(request.phoneNumber)
        return ResponseEntity.ok(MessageResponse("Код відправлено на новий номер"))
    }

    @PostMapping("/profile/delete-request")
    fun requestAccountDeletion(@AuthenticationPrincipal userDetails: UserDetails): ResponseEntity<MessageResponse> {
        val driver = getDriverFromUser(userDetails)
        driverService.requestAccountDeletion(driver)
        driverLocationService.clearLocation(driver.id!!)
        return ResponseEntity.ok(MessageResponse("Акаунт додано в чергу на видалення"))
    }

    @PostMapping("/profile/restore")
    fun restoreAccount(@AuthenticationPrincipal userDetails: UserDetails): ResponseEntity<DriverDto> {
        val driver = getDriverFromUser(userDetails)
        val restoredDriver = driverService.restoreAccount(driver)
        return ResponseEntity.ok(restoredDriver)
    }


    
    @PostMapping("/profile/change-phone/confirm-new")
    fun confirmNewPhone(
        @AuthenticationPrincipal userDetails: UserDetails,
        @RequestBody request: ChangePhoneConfirmRequest
    ): ResponseEntity<LoginResponse> {
        val driver = getDriverFromUser(userDetails)
        val updatedUser = driverService.changePhone(driver, request.newPhone, request.code, request.changeToken)
        val newToken = jwtUtils.generateToken(updatedUser, updatedUser.uuid, updatedUser.role.name)
        val newRefreshToken = authService.createRefreshToken(updatedUser.id!!)
        
        return ResponseEntity.ok(LoginResponse(
            token = newToken,
            refreshToken = newRefreshToken.token,
            role = updatedUser.role.name,
            userId = updatedUser.id!!,
            phoneNumber = updatedUser.userPhone ?: "",
            fullName = updatedUser.fullName ?: "",
            isNewUser = false,
            isPendingDeletion = false
        ))
    }

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

    @DeleteMapping("/location")
fun logoutFromMap(@AuthenticationPrincipal user: User): ResponseEntity<Void> {
    if (user !is Driver) throw ResponseStatusException(HttpStatus.FORBIDDEN)
    
    driverLocationService.clearLocation(user.id!!)
    return ResponseEntity.ok().build()
}

    @GetMapping("/forms/add-car")
    fun getAddCarForm(@RequestParam token: String): ResponseEntity<Void> {
        val reactFormUrl = "/add-car/index.html?token=$token"
        return ResponseEntity.status(HttpStatus.FOUND)
            .header("Location", reactFormUrl)
            .build()
    }

    @PostMapping("/cars/add", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun addCar(
        @AuthenticationPrincipal userDetails: UserDetails, // <--- ИЗМЕНЕНО: Защита OWASP через контекст сессии Spring Security вместо токена в URL
        @RequestParam("data") carJson: String,
        request: MultipartHttpServletRequest
    ): ResponseEntity<Map<String, Any>> {
        val driver = getDriverFromUser(userDetails) // <--- ИЗМЕНЕНО: Извлекаем надежно привязанного водителя
        
        val savedPhotos = mutableMapOf<String, String>()
        
        // --- ЗАЩИТА: Список разрешенных безопасных расширений изображений (Anti-Webshell / Anti-XSS) ---
        val allowedExtensions = listOf("jpg", "jpeg", "png", "svg")

        request.fileMap.forEach { (key, file) ->
            if (!file.isEmpty) {
                val originalFilename = file.originalFilename ?: "file"
                val extension = originalFilename.substringAfterLast('.', "").lowercase()
                
                if (extension !in allowedExtensions) {
                    throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Недопустимий тип файлу: .$extension. Дозволені тільки зображення.")
                }
                
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
        val year = try { if (node.has("year")) node.get("year").asInt() else 2020 } catch (e: Exception) { 2020 }

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
            photoTrunk      = photo("photo_trunk"),
            photoUrl = photo("photo_right"), 
            status = CarStatus.PENDING 
        )
        
        carRepository.save(newCar)

        return ResponseEntity.ok(mapOf(
            "success" to true,
            "message" to "Заявка прийнята! Всі документи та фото отримано. ${make} ${model} відправлено на перевірку.",
            "carId" to newCar.id
        ))
    }

    private fun validateTokenAndGetDriver(token: String): Driver {
    try {
        // Извлекаем UUID и ищем через репозиторий по UUID
        val driverUuid = jwtUtils.extractUserUuid(token)
        return driverRepository.findByUuid(driverUuid)
            .orElseThrow { RuntimeException("Driver not found") }
    } catch (e: Exception) {
         throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token")
    }
}

    @GetMapping("/cars")
    fun getMyCars(@AuthenticationPrincipal userDetails: UserDetails): ResponseEntity<List<CarDto>> {
        val driver = getDriverFromUser(userDetails)
        val cars = carRepository.findAllByDriver(driver)
        
        // ИСПРАВЛЕНО: Преобразуем список Car в список CarDto, чтобы сгенерировались полные веб-ссылки на изображения
        val dtos = cars.map { CarDto(it) }
        return ResponseEntity.ok(dtos)
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