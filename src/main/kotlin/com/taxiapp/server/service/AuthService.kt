package com.taxiapp.server.service

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
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
import java.util.Collections
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
    private val fileStorageService: FileStorageService,
    private val refreshTokenRepository: RefreshTokenRepository,
    @org.springframework.beans.factory.annotation.Value("\${jwt.refresh-expiration}") private val refreshTokenDurationMs: Long,
    @org.springframework.beans.factory.annotation.Value("\${google.client-id}") private val googleClientId: String
) {

    // --- НОРМАЛИЗАЦИЯ НОМЕРА ---
    private fun normalizePhone(phone: String): String {
        return if (phone.startsWith("+380")) {
            "0" + phone.substring(4)
        } else {
            phone
        }
    }

    @Transactional
    fun login(request: LoginRequest): LoginResponse {
        val loginCandidate = request.login
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
            authenticationManager.authenticate(UsernamePasswordAuthenticationToken(normalizedLogin, request.password))
        } catch (e: Exception) { throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Невірний логін або пароль") }
        
        val userDetails = userDetailsService.loadUserByUsername(normalizedLogin)
        val token = jwtUtils.generateToken(userDetails, user.id, user.role.name)
        val isPending = user.deletionRequestedAt != null
        
        val refreshToken = createRefreshToken(user.id)
        return LoginResponse(
            token = token, 
            refreshToken = refreshToken.token, 
            userId = user.id, 
            phoneNumber = user.userPhone ?: "", 
            fullName = user.fullName ?: "Водій", 
            role = user.role.name, 
            isNewUser = false,
            isPendingDeletion = isPending
        )
    }

    // --- GOOGLE LOGIN / REGISTER ---
    @Transactional
    fun verifyGoogleTokenAndLogin(request: GoogleAuthRequest): LoginResponse {
        val verifier = GoogleIdTokenVerifier.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance())
            .setAudience(Collections.singletonList(googleClientId))
            .build()

        val idToken: GoogleIdToken? = try {
            verifier.verify(request.idToken)
        } catch (e: Exception) {
            null
        }

        if (idToken != null) {
            val payload: GoogleIdToken.Payload = idToken.payload

            val email: String = payload.email
            val name: String = payload.get("name") as String? ?: "Клієнт"
            
            var user = userRepository.findByEmail(email)
            var isNew = false

            if (user == null) {
                isNew = true
                val newClient = Client().apply {
                    this.email = email
                    this.userLogin = email
                    this.fullName = name
                    this.passwordHash = passwordEncoder.encode("google_login_stub")
                    this.role = Role.CLIENT
                    this.isBlocked = false
                }
                user = clientRepository.save(newClient)
            }

            if (user.isBlocked) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Акаунт заблоковано")

            val userDetails = userDetailsService.loadUserByUsername(user.userLogin ?: user.email!!)
            val token = jwtUtils.generateToken(userDetails, user.id, user.role.name)
            val refreshToken = createRefreshToken(user.id)
            val isPending = user.deletionRequestedAt != null

            return LoginResponse(
                token = token,
                refreshToken = refreshToken.token,
                userId = user.id,
                phoneNumber = user.userPhone ?: "", 
                fullName = user.fullName,
                role = user.role.name,
                isNewUser = isNew,
                isPendingDeletion = isPending
            )

        } else {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Недійсний Google ID Token")
        }
    }

    // --- DRIVER LOGIN SMS ---
    @Transactional
    fun requestDriverLoginSms(request: SmsRequestDto): MessageResponse {
        val normalizedPhone = normalizePhone(request.phoneNumber)

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
        val normalizedPhone = normalizePhone(request.phoneNumber)
        
        checkDriverSmsCode(normalizedPhone, request.code) 

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
        val isPending = user.deletionRequestedAt != null
        
        val refreshToken = createRefreshToken(user.id)
        return LoginResponse(
            token = token, 
            refreshToken = refreshToken.token, 
            userId = user.id, 
            phoneNumber = user.userPhone ?: "", 
            fullName = user.fullName ?: "Водій", 
            role = user.role.name, 
            isNewUser = false,
            isPendingDeletion = isPending
        )
    }

    // --- CLIENT LOGIN / REGISTER ---
    @Transactional
    fun requestSmsCode(request: SmsRequestDto): MessageResponse {
        val normalizedPhone = normalizePhone(request.phoneNumber)
        sendSmsInternal(normalizedPhone)
        return MessageResponse("Код надіслано")
    }

    @Transactional
    fun verifySmsCodeAndLogin(request: SmsVerifyDto): LoginResponse {
        val normalizedPhone = normalizePhone(request.phoneNumber)
        
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
        val isPending = user.deletionRequestedAt != null
        
        val refreshToken = createRefreshToken(user.id)
        return LoginResponse(
            token = token, 
            refreshToken = refreshToken.token, 
            userId = user.id, 
            phoneNumber = user.userPhone ?: "", 
            fullName = user.fullName ?: "Клієнт", 
            role = user.role.name, 
            isNewUser = isNew,
            isPendingDeletion = isPending
        )
    }

    // --- DRIVER REGISTRATION FLOW ---
    @Transactional
    fun requestDriverRegistrationSms(request: SmsRequestDto): MessageResponse {
        val normalizedPhone = normalizePhone(request.phoneNumber)
        
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
        
        val existingSms = smsCodeRepository.findByUserPhone(phone).orElse(null)

        if (existingSms != null) {
            existingSms.code = code
            existingSms.expiresAt = LocalDateTime.now().plusMinutes(10)
            smsCodeRepository.save(existingSms)
        } else {
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
        val normalizedLogin = if (userLogin.startsWith("+380")) normalizePhone(userLogin) else userLogin
        
        val user = userRepository.findByUserPhone(normalizedLogin).orElseGet { 
                userRepository.findByUserLogin(normalizedLogin).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Користувача не знайдено") }
            }
        user.fcmToken = token
        userRepository.save(user)
    }

    @Transactional
    fun registerDriver(request: RegisterDriverRequest, files: Map<String, MultipartFile>): MessageResponse {
        val normalizedPhone = normalizePhone(request.phoneNumber) 

        if (userRepository.existsByUserPhone(normalizedPhone)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Цей номер вже зареєстрований")
        }

        checkDriverSmsCode(normalizedPhone, request.smsCode)
        smsCodeRepository.findByUserPhone(normalizedPhone).ifPresent { smsCodeRepository.delete(it) }

        val avatarUrl = files["avatar"]?.let { fileStorageService.storeFile(it) }
        val licenseFrontUrl = files["driverLicenseFront"]?.let { fileStorageService.storeFile(it) }
        val licenseBackUrl = files["driverLicenseBack"]?.let { fileStorageService.storeFile(it) }
        val techFrontUrl = files["techPassportFront"]?.let { fileStorageService.storeFile(it) }
        val techBackUrl = files["techPassportBack"]?.let { fileStorageService.storeFile(it) }
        val insuranceUrl = files["insurance"]?.let { fileStorageService.storeFile(it) }
        val carPhotoUrl = files["carPhoto"]?.let { fileStorageService.storeFile(it) }
        val carFrontUrl = files["carFront"]?.let { fileStorageService.storeFile(it) }
        val carBackUrl = files["carBack"]?.let { fileStorageService.storeFile(it) }
        val carLeftUrl = files["carLeft"]?.let { fileStorageService.storeFile(it) }
        val carRightUrl = files["carRight"]?.let { fileStorageService.storeFile(it) }
        val carIntFrontUrl = files["carInteriorFront"]?.let { fileStorageService.storeFile(it) }
        val carIntBackUrl = files["carInteriorBack"]?.let { fileStorageService.storeFile(it) }

        val driver = Driver().apply {
            fullName = request.fullName
            userLogin = normalizedPhone 
            userPhone = normalizedPhone 
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

    @Transactional
    fun verifySmsAndLinkPhone(request: SmsVerifyDto, principalName: String): LoginResponse {
        val normalizedPhone = normalizePhone(request.phoneNumber)
        
        // 1. Перевіряємо СМС код
        checkDriverSmsCode(normalizedPhone, request.code)
        smsCodeRepository.findByUserPhone(normalizedPhone).ifPresent { smsCodeRepository.delete(it) }
        
        // 2. Знаходимо поточного користувача (тимчасовий Google-акаунт)
        val currentUser = userRepository.findByUserLogin(principalName).orElseGet {
            userRepository.findByEmail(principalName) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Користувача не знайдено")
        }

        // 3. Перевіряємо, чи є вже акаунт з таким номером телефону
        val existingUserOptional = userRepository.findByUserPhone(normalizedPhone)

        val finalUser = if (existingUserOptional.isPresent && existingUserOptional.get().id != currentUser.id) {
            // ==========================================
            // ЛОГІКА ЗЛИТТЯ (MERGE) АКАУНТІВ
            // ==========================================
            val existingUser = existingUserOptional.get()

            // Зберігаємо корисні дані з Google перед видаленням
            val googleEmail = currentUser.email
            val googleName = currentUser.fullName

            // 1. Видаляємо Refresh токени тимчасового акаунту
            refreshTokenRepository.deleteByUser(currentUser)
            
            // 2. Змінюємо унікальні поля перед видаленням, щоб уникнути конфліктів у базі
            currentUser.email = "deleted_${java.util.UUID.randomUUID()}@taxi.com"
            currentUser.userLogin = "deleted_${java.util.UUID.randomUUID()}"
            userRepository.saveAndFlush(currentUser)
            
            // 3. Видаляємо тимчасовий Google-акаунт з бази
            userRepository.delete(currentUser)
            userRepository.flush() // Фіксуємо видалення в БД

            // 4. Оновлюємо старий (основний) акаунт новими даними
            existingUser.email = googleEmail
            
            // Якщо старий акаунт не мав імені (був "Райдер"), беремо ім'я з Google
            if (existingUser.fullName == "Райдер" || existingUser.fullName == "Клієнт") {
                existingUser.fullName = googleName
            }
            
            userRepository.save(existingUser)
            existingUser // Тепер це наш головний акаунт для логіну
            
        } else {
            // ==========================================
            // ЗВИЧАЙНА ПРИВ'ЯЗКА (номер був вільний)
            // ==========================================
            currentUser.userPhone = normalizedPhone
            if (currentUser.userLogin == currentUser.email) {
                currentUser.userLogin = normalizedPhone 
            }
            userRepository.save(currentUser)
            currentUser
        }

        // 4. Генеруємо нові токени для фінального користувача
        val userDetails = userDetailsService.loadUserByUsername(finalUser.userLogin!!)
        val token = jwtUtils.generateToken(userDetails, finalUser.id, finalUser.role.name)
        val refreshToken = createRefreshToken(finalUser.id)
        
        return LoginResponse(
            token = token, 
            refreshToken = refreshToken.token, 
            userId = finalUser.id, 
            phoneNumber = finalUser.userPhone ?: "", 
            fullName = finalUser.fullName ?: "Клієнт", 
            role = finalUser.role.name, 
            isNewUser = false,
            isPendingDeletion = finalUser.deletionRequestedAt != null
        )
    }

    @Transactional
    fun createRefreshToken(userId: Long): com.taxiapp.server.model.auth.RefreshToken {
        val user = userRepository.findById(userId).orElseThrow { 
            ResponseStatusException(HttpStatus.NOT_FOUND, "Користувача не знайдено") 
        }
        
        refreshTokenRepository.deleteByUser(user)
        refreshTokenRepository.flush()

        val refreshToken = com.taxiapp.server.model.auth.RefreshToken().apply {
            this.user = user
            this.token = jwtUtils.generateRefreshToken()
            this.expiryDate = java.time.Instant.now().plusMillis(refreshTokenDurationMs)
        }
        return refreshTokenRepository.save(refreshToken)
    }

    @Transactional
    fun refreshToken(request: TokenRefreshRequest): LoginResponse {
        val refreshToken = refreshTokenRepository.findByToken(request.refreshToken)
            .orElseThrow { ResponseStatusException(HttpStatus.FORBIDDEN, "Недійсний Refresh Token") }

        if (refreshToken.expiryDate < java.time.Instant.now()) {
            refreshTokenRepository.delete(refreshToken)
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Refresh Token прострочений. Авторизуйтесь знову.")
        }

        val user = refreshToken.user
        if (user.isBlocked) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Акаунт заблоковано")

        val userDetails = userDetailsService.loadUserByUsername(user.userLogin ?: user.userPhone!!)
        val newAccessToken = jwtUtils.generateToken(userDetails, user.id, user.role.name)
        val isPending = user.deletionRequestedAt != null
        
        return LoginResponse(
            token = newAccessToken, 
            refreshToken = refreshToken.token, 
            userId = user.id, 
            phoneNumber = user.userPhone ?: "", 
            fullName = user.fullName ?: "Користувач", 
            role = user.role.name, 
            isNewUser = false, 
            isPendingDeletion = isPending
        )
    }
}