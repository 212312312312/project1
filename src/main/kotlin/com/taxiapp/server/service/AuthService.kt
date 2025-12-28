package com.taxiapp.server.service

import com.taxiapp.server.dto.auth.*
import com.taxiapp.server.model.auth.SmsVerificationCode
import com.taxiapp.server.model.user.Car
import com.taxiapp.server.model.user.Client
import com.taxiapp.server.model.user.Driver
import com.taxiapp.server.model.enums.Role
import com.taxiapp.server.repository.*
import com.taxiapp.server.security.JwtUtils
import com.taxiapp.server.security.UserDetailsServiceImpl
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
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
    private val smsService: SmsService
) {

    // --- 1. ВХІД ПО ПАРОЛЮ (ВИПРАВЛЕНО NPE) ---
    fun login(request: LoginRequest): LoginResponse {
        println(">>> LOGIN: Початок входу для ${request.login}")

        // 1. Шукаємо користувача (Логін або Телефон)
        var userOptional = userRepository.findByUserLogin(request.login)
        if (userOptional.isEmpty) {
            userOptional = userRepository.findByUserPhone(request.login)
        }

        if (userOptional.isEmpty) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Користувача не знайдено")
        }

        val user = userOptional.get()

        if (user.isBlocked) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Акаунт заблоковано")
        }

        // 2. Аутентифікація (Перевірка пароля)
        try {
            // Важливо: ми передаємо request.login (те, що ввів юзер), щоб уникнути null в user.userLogin
            authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken(request.login, request.password)
            )
            println(">>> LOGIN: Пароль вірний!")
        } catch (e: Exception) {
            println(">>> LOGIN ERROR: Невірний пароль")
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Невірний логін або пароль")
        }

        // 3. Генерація токена (БЕЗПЕЧНО)
        // Використовуємо request.login, тому що UserDetailsServiceImpl тепер вміє шукати по телефону
        val userDetails = userDetailsService.loadUserByUsername(request.login)
        
        // Передаємо ID та Role (enum to string)
        val token = jwtUtils.generateToken(userDetails, user.id, user.role.name)

        println(">>> LOGIN: Успіх! Токен створено.")

        return LoginResponse(
            token = token,
            userId = user.id,
            phoneNumber = user.userPhone ?: "", // Безпечна перевірка на null
            fullName = user.fullName ?: "Водій", // Безпечна перевірка на null
            role = user.role.name,
            isNewUser = false
        )
    }

    // --- 2. SMS (Клієнт) ---
    @Transactional
    fun requestSmsCode(request: SmsRequestDto): MessageResponse {
        val phone = request.phoneNumber
        if (blacklistRepository.existsByPhoneNumber(phone)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Номер заблоковано")
        }
        val code = (100000 + Random().nextInt(900000)).toString()
        
        smsCodeRepository.findByUserPhone(phone).ifPresent { smsCodeRepository.delete(it) }
        
        val smsEntity = SmsVerificationCode(
            userPhone = phone,
            code = code,
            expiresAt = LocalDateTime.now().plusMinutes(5)
        )
        smsCodeRepository.save(smsEntity)
        
        smsService.sendSms(phone, "Ваш код: $code")
        return MessageResponse("Код надіслано")
    }

    @Transactional
    fun verifySmsCodeAndLogin(request: SmsVerifyDto): LoginResponse {
        val phoneNumber = request.phoneNumber
        val code = request.code

        val smsEntity = smsCodeRepository.findByUserPhone(phoneNumber)
            .orElseThrow { ResponseStatusException(HttpStatus.BAD_REQUEST, "Код не знайдено") }

        if (smsEntity.code != code) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Невірний код")
        smsCodeRepository.delete(smsEntity)

        var user = userRepository.findByUserPhone(phoneNumber).orElse(null)
        var isNew = false

        if (user == null) {
            isNew = true
            val newClient = Client().apply {
                userPhone = phoneNumber
                userLogin = phoneNumber
                fullName = "Райдер"
                passwordHash = passwordEncoder.encode("sms_login")
                role = Role.CLIENT
                isBlocked = false
            }
            user = clientRepository.save(newClient)
        }

        val userDetails = userDetailsService.loadUserByUsername(user.userLogin ?: phoneNumber)
        val token = jwtUtils.generateToken(userDetails, user.id, user.role.name)

        return LoginResponse(
            token = token,
            userId = user.id,
            phoneNumber = user.userPhone ?: "",
            fullName = user.fullName ?: "Клієнт",
            role = user.role.name,
            isNewUser = isNew
        )
    }

    // --- 3. РЕЄСТРАЦІЯ ВОДІЯ ---
    @Transactional
    fun registerDriver(request: RegisterDriverRequest): MessageResponse {
        if (userRepository.existsByUserPhone(request.phoneNumber)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Цей номер вже зареєстрований")
        }

        val car = Car(
            make = request.make,
            model = request.model,
            color = request.color,
            plateNumber = request.plateNumber,
            vin = request.vin,
            year = request.year
        )

        val driver = Driver().apply {
            fullName = request.fullName
            userLogin = request.phoneNumber // Логін = Телефон
            userPhone = request.phoneNumber
            passwordHash = passwordEncoder.encode(request.password)
            role = Role.DRIVER
            isBlocked = false
            isOnline = false
            this.car = car
        }
        
        driverRepository.save(driver)
        return MessageResponse("Водія зареєстровано")
    }

    fun updateFcmToken(userLogin: String, token: String) {
        // Шукаємо користувача по телефону (логіну)
        val user = userRepository.findByUserPhone(userLogin)
            .orElseGet { 
                userRepository.findByUserLogin(userLogin).orElseThrow {
                    ResponseStatusException(HttpStatus.NOT_FOUND, "Користувача не знайдено")
                }
            }
            
        // Оновлюємо токен
        user.fcmToken = token
        userRepository.save(user)
    }
}