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

    // ... (Методи login, requestSms, verifySms, requestDriverRegistrationSms БЕЗ ЗМІН) ...

    fun login(request: LoginRequest): LoginResponse {
        println(">>> LOGIN: Початок входу для ${request.login}")
        var userOptional = userRepository.findByUserLogin(request.login)
        if (userOptional.isEmpty) userOptional = userRepository.findByUserPhone(request.login)
        if (userOptional.isEmpty) throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Користувача не знайдено")
        val user = userOptional.get()
        if (user is Driver) {
            if (user.registrationStatus == RegistrationStatus.PENDING) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Ваша заявка на реєстрацію ще на розгляді.")
            if (user.registrationStatus == RegistrationStatus.REJECTED) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Вашу заявку відхилено.")
        }
        if (user.isBlocked) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Акаунт заблоковано")
        try {
            authenticationManager.authenticate(UsernamePasswordAuthenticationToken(request.login, request.password))
        } catch (e: Exception) { throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Невірний логін або пароль") }
        val userDetails = userDetailsService.loadUserByUsername(request.login)
        val token = jwtUtils.generateToken(userDetails, user.id, user.role.name)
        return LoginResponse(token, user.id, user.userPhone ?: "", user.fullName ?: "Водій", user.role.name, false)
    }

    @Transactional
    fun requestSmsCode(request: SmsRequestDto): MessageResponse {
        sendSmsInternal(request.phoneNumber)
        return MessageResponse("Код надіслано")
    }

    @Transactional
    fun verifySmsCodeAndLogin(request: SmsVerifyDto): LoginResponse {
        val phone = request.phoneNumber
        checkDriverSmsCode(phone, request.code)
        smsCodeRepository.findByUserPhone(phone).ifPresent { smsCodeRepository.delete(it) }
        var user = userRepository.findByUserPhone(phone).orElse(null)
        var isNew = false
        if (user == null) {
            isNew = true
            val newClient = Client().apply {
                userPhone = phone; userLogin = phone; fullName = "Райдер"
                passwordHash = passwordEncoder.encode("sms_login"); role = Role.CLIENT; isBlocked = false
            }
            user = clientRepository.save(newClient)
        }
        val userDetails = userDetailsService.loadUserByUsername(user.userLogin ?: phone)
        val token = jwtUtils.generateToken(userDetails, user.id, user.role.name)
        return LoginResponse(token, user.id, user.userPhone ?: "", user.fullName ?: "Клієнт", user.role.name, isNew)
    }

    @Transactional
    fun requestDriverRegistrationSms(request: SmsRequestDto): MessageResponse {
        if (userRepository.existsByUserPhone(request.phoneNumber)) throw ResponseStatusException(HttpStatus.CONFLICT, "Цей номер вже зареєстрований")
        sendSmsInternal(request.phoneNumber)
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
        if (blacklistRepository.existsByPhoneNumber(phone)) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Номер заблоковано")
        val code = (100000 + Random().nextInt(900000)).toString()
        smsCodeRepository.findByUserPhone(phone).ifPresent { smsCodeRepository.delete(it) }
        val smsEntity = SmsVerificationCode(userPhone = phone, code = code, expiresAt = LocalDateTime.now().plusMinutes(10))
        smsCodeRepository.save(smsEntity)
        smsService.sendSms(phone, "Ваш код таксі: $code")
    }

    fun updateFcmToken(userLogin: String, token: String) {
        val user = userRepository.findByUserPhone(userLogin).orElseGet { 
                userRepository.findByUserLogin(userLogin).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Користувача не знайдено") }
            }
        user.fcmToken = token
        userRepository.save(user)
    }

    // --- ТУТ БУЛА ПОМИЛКА: request.vin більше не існує ---
    @Transactional
    fun registerDriver(request: RegisterDriverRequest, files: Map<String, MultipartFile>): MessageResponse {
        if (userRepository.existsByUserPhone(request.phoneNumber)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Цей номер вже зареєстрований")
        }

        checkDriverSmsCode(request.phoneNumber, request.smsCode)
        smsCodeRepository.findByUserPhone(request.phoneNumber).ifPresent { smsCodeRepository.delete(it) }

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
            userLogin = request.phoneNumber
            userPhone = request.phoneNumber
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

        // 2. Створюємо машину (ВИПРАВЛЕНО: vin = "")
        val car = Car(
            make = request.make,
            model = request.model,
            color = request.color,
            plateNumber = request.plateNumber,
            vin = "", // <--- ТУТ БУЛА ПОМИЛКА. Ставимо порожній рядок.
            year = request.year,
            carType = request.carType
        ).apply {
            this.techPassportFront = techFrontUrl
            this.techPassportBack = techBackUrl
            this.insurancePhoto = insuranceUrl 
            
            // Назначаємо нові фото (якщо старі не потрібні, їх можна не сетати, або залишити для сумісності)
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