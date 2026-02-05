package com.taxiapp.server.service

import com.taxiapp.server.dto.auth.*
import com.taxiapp.server.model.auth.SmsVerificationCode
import com.taxiapp.server.model.user.Car
import com.taxiapp.server.model.user.Client
import com.taxiapp.server.model.user.Driver
import com.taxiapp.server.model.enums.Role
import com.taxiapp.server.model.enums.RegistrationStatus
import com.taxiapp.server.repository.*
import com.taxiapp.server.security.JwtUtils
import com.taxiapp.server.security.UserDetailsServiceImpl
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime
import java.util.Random

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val clientRepository: ClientRepository,
    private val driverRepository: DriverRepository,
    private val smsCodeRepository: SmsVerificationCodeRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtUtils: JwtUtils,
    private val authenticationManager: AuthenticationManager,
    private val userDetailsService: UserDetailsServiceImpl,
    private val blacklistRepository: BlacklistRepository,
    private val smsService: SmsService,
    private val fileStorageService: FileStorageService
) {

    // --- НОРМАЛИЗАЦИЯ НОМЕРА ---
    // Приводим +380XXXXXXXXX к 0XXXXXXXXX (формат базы данных)
    private fun normalizePhone(phone: String): String {
        return if (phone.startsWith("+380")) {
            "0" + phone.substring(4)
        } else {
            phone
        }
    }

    fun login(request: LoginRequest): LoginResponse {
        val loginCandidate = request.login
        // Если логин похож на телефон (начинается с +380), нормализуем его
        val normalizedLogin = if (loginCandidate.startsWith("+380")) normalizePhone(loginCandidate) else loginCandidate

        println(">>> LOGIN: Початок входу для $normalizedLogin")
        
        var userOptional = userRepository.findByUserLogin(normalizedLogin)
        if (userOptional.isEmpty) userOptional = userRepository.findByUserPhone(normalizedLogin)
        
        if (userOptional.isEmpty) throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Користувача не знайдено")
        val user = userOptional.get()
        
        if (user is Driver) {
            if (user.registrationStatus == RegistrationStatus.PENDING) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Ваша заявка на реєстрацію ще на розгляді.")
            if (user.registrationStatus == RegistrationStatus.REJECTED) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Вашу заявку відхилено.")
        }
        if (user.isBlocked) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Акаунт заблоковано")
        
        try {
            // Аутентификация тоже по нормализованному логину, если это телефон
            authenticationManager.authenticate(UsernamePasswordAuthenticationToken(normalizedLogin, request.password))
        } catch (e: Exception) { throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Невірний логін або пароль") }
        
        val userDetails = userDetailsService.loadUserByUsername(normalizedLogin)
        val token = jwtUtils.generateToken(userDetails, user.id, user.role.name)
        return LoginResponse(token, user.id, user.userPhone ?: "", user.fullName ?: "Водій", user.role.name, false)
    }

    // --- DRIVER LOGIN SMS (NEW) ---
    @Transactional
    fun requestDriverLoginSms(request: SmsRequestDto): MessageResponse {
        val normalizedPhone = normalizePhone(request.phoneNumber) // <--- НОРМАЛИЗАЦИЯ

        val user = userRepository.findByUserPhone(normalizedPhone).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Водія з таким номером не знайдено")
        }
        if (user !is Driver) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Цей номер не належить водію")
        }
        sendSmsInternal(normalizedPhone)
        return MessageResponse("Код надіслано")
    }

    @Transactional
    fun verifyDriverLoginSms(request: SmsVerifyDto): LoginResponse {
        val normalizedPhone = normalizePhone(request.phoneNumber) // <--- НОРМАЛИЗАЦИЯ
        
        checkDriverSmsCode(normalizedPhone, request.code) 
        // smsCodeRepository.findByUserPhone(normalizedPhone).ifPresent { smsCodeRepository.delete(it) } // <--- ПОКА ЗАКОММЕНТИРОВАНО ДЛЯ ТЕСТОВ

        val user = userRepository.findByUserPhone(normalizedPhone).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Користувача не знайдено")
        }

        if (user is Driver) {
            if (user.registrationStatus == RegistrationStatus.PENDING) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Ваша заявка на реєстрацію ще на розгляді.")
            if (user.registrationStatus == RegistrationStatus.REJECTED) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Вашу заявку відхилено.")
        } else {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Це не акаунт водія")
        }

        if (user.isBlocked) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Акаунт заблоковано")

        val userDetails = userDetailsService.loadUserByUsername(user.userLogin ?: normalizedPhone)
        val token = jwtUtils.generateToken(userDetails, user.id, user.role.name)
        return LoginResponse(token, user.id, user.userPhone ?: "", user.fullName ?: "Водій", user.role.name, false)
    }

    // --- CLIENT LOGIN / REGISTER ---
    @Transactional
    fun requestSmsCode(request: SmsRequestDto): MessageResponse {
        val normalizedPhone = normalizePhone(request.phoneNumber) // <--- НОРМАЛИЗАЦИЯ
        sendSmsInternal(normalizedPhone)
        return MessageResponse("Код надіслано")
    }

    @Transactional
    fun verifySmsCodeAndLogin(request: SmsVerifyDto): LoginResponse {
        val normalizedPhone = normalizePhone(request.phoneNumber) // <--- НОРМАЛИЗАЦИЯ
        
        checkDriverSmsCode(normalizedPhone, request.code)
        smsCodeRepository.findByUserPhone(normalizedPhone).ifPresent { smsCodeRepository.delete(it) }
        
        var user = userRepository.findByUserPhone(normalizedPhone).orElse(null)
        var isNew = false
        if (user == null) {
            isNew = true
            val newClient = Client().apply {
                userPhone = normalizedPhone; userLogin = normalizedPhone; fullName = "Райдер"
                passwordHash = passwordEncoder.encode("sms_login"); role = Role.CLIENT; isBlocked = false
            }
            user = clientRepository.save(newClient)
        }
        val userDetails = userDetailsService.loadUserByUsername(user.userLogin ?: normalizedPhone)
        val token = jwtUtils.generateToken(userDetails, user.id, user.role.name)
        return LoginResponse(token, user.id, user.userPhone ?: "", user.fullName ?: "Клієнт", user.role.name, isNew)
    }

    // --- DRIVER REGISTRATION FLOW ---
    @Transactional
    fun requestDriverRegistrationSms(request: SmsRequestDto): MessageResponse {
        val normalizedPhone = normalizePhone(request.phoneNumber) // <--- НОРМАЛИЗАЦИЯ
        
        if (userRepository.existsByUserPhone(normalizedPhone)) throw ResponseStatusException(HttpStatus.CONFLICT, "Цей номер вже зареєстрований")
        sendSmsInternal(normalizedPhone)
        return MessageResponse("Код надіслано")
    }

    fun checkDriverSmsCode(phone: String, code: String) {
        val smsEntity = smsCodeRepository.findByUserPhone(phone)
            .orElseThrow { ResponseStatusException(HttpStatus.BAD_REQUEST, "Код не знайдено") }
        if (smsEntity.code != code) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Невірний код")
        if (smsEntity.expiresAt.isBefore(LocalDateTime.now())) {
            smsCodeRepository.delete(smsEntity)
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Код застарів")
        }
    }

    private fun sendSmsInternal(phone: String) {
        if (blacklistRepository.existsByPhoneNumber(phone)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Номер заблоковано")
        }
        
        val code = (100000 + Random().nextInt(900000)).toString()
        
        // Попытка найти существующую запись
        val existingSms = smsCodeRepository.findByUserPhone(phone).orElse(null)

        if (existingSms != null) {
            // Если запись есть — обновляем её
            existingSms.code = code
            existingSms.expiresAt = LocalDateTime.now().plusMinutes(10)
            smsCodeRepository.save(existingSms)
        } else {
            // Если записи нет — создаем новую
            val smsEntity = SmsVerificationCode(
                userPhone = phone, 
                code = code, 
                expiresAt = LocalDateTime.now().plusMinutes(10)
            )
            smsCodeRepository.save(smsEntity)
        }

        smsService.sendSms(phone, "Ваш код таксі: $code")
    }

    fun updateFcmToken(userLogin: String, token: String) {
        // При обновлении токена userLogin может прийти как +380..., так и 0...
        // Но обычно userLogin == userPhone в БД
        val normalizedLogin = if (userLogin.startsWith("+380")) normalizePhone(userLogin) else userLogin
        
        val user = userRepository.findByUserPhone(normalizedLogin).orElseGet { 
                userRepository.findByUserLogin(normalizedLogin).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Користувача не знайдено") }
            }
        user.fcmToken = token
        userRepository.save(user)
    }

    @Transactional
    fun registerDriver(request: RegisterDriverRequest, files: Map<String, MultipartFile>): MessageResponse {
        val normalizedPhone = normalizePhone(request.phoneNumber) // <--- НОРМАЛИЗАЦИЯ

        if (userRepository.existsByUserPhone(normalizedPhone)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Цей номер вже зареєстрований")
        }

        checkDriverSmsCode(normalizedPhone, request.smsCode)
        smsCodeRepository.findByUserPhone(normalizedPhone).ifPresent { smsCodeRepository.delete(it) }

        // Зберігаємо файли
        val avatarUrl = files["avatar"]?.let { fileStorageService.storeFile(it) }
        val licenseFrontUrl = files["driverLicenseFront"]?.let { fileStorageService.storeFile(it) }
        val licenseBackUrl = files["driverLicenseBack"]?.let { fileStorageService.storeFile(it) }
        
        val techFrontUrl = files["techPassportFront"]?.let { fileStorageService.storeFile(it) }
        val techBackUrl = files["techPassportBack"]?.let { fileStorageService.storeFile(it) }
        val insuranceUrl = files["insurance"]?.let { fileStorageService.storeFile(it) }
        val carPhotoUrl = files["carPhoto"]?.let { fileStorageService.storeFile(it) }
        
        // Нові фото
        val carFrontUrl = files["carFront"]?.let { fileStorageService.storeFile(it) }
        val carBackUrl = files["carBack"]?.let { fileStorageService.storeFile(it) }
        val carLeftUrl = files["carLeft"]?.let { fileStorageService.storeFile(it) }
        val carRightUrl = files["carRight"]?.let { fileStorageService.storeFile(it) }
        val carIntFrontUrl = files["carInteriorFront"]?.let { fileStorageService.storeFile(it) }
        val carIntBackUrl = files["carInteriorBack"]?.let { fileStorageService.storeFile(it) }

        // 1. Створюємо водія
        val driver = Driver().apply {
            fullName = request.fullName
            userLogin = normalizedPhone // Сохраняем в БД как 0...
            userPhone = normalizedPhone // Сохраняем в БД как 0...
            passwordHash = passwordEncoder.encode(request.password)
            this.email = request.email
            this.rnokpp = request.rnokpp
            this.driverLicense = request.driverLicense
            
            this.photoUrl = avatarUrl 
            this.driverLicenseFront = licenseFrontUrl
            this.driverLicenseBack = licenseBackUrl
            
            role = Role.DRIVER
            isBlocked = false
            isOnline = false
            registrationStatus = RegistrationStatus.PENDING
        }

        // 2. Створюємо машину
        val car = Car(
            make = request.make,
            model = request.model,
            color = request.color,
            plateNumber = request.plateNumber,
            vin = "", 
            year = request.year,
            carType = request.carType
        ).apply {
            this.techPassportFront = techFrontUrl
            this.techPassportBack = techBackUrl
            this.insurancePhoto = insuranceUrl 
            
            this.photoFront = carFrontUrl ?: carPhotoUrl
            this.photoBack = carBackUrl
            this.photoLeft = carLeftUrl
            this.photoRight = carRightUrl
            this.photoSeatsFront = carIntFrontUrl
            this.photoSeatsBack = carIntBackUrl
            
            this.driver = driver 
        }

        driver.car = car
        driver.cars.add(car)
        driverRepository.save(driver)

        return MessageResponse("Заявку прийнято. Очікуйте підтвердження.")
    }
}