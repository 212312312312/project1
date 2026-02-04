package com.taxiapp.server.controller

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.taxiapp.server.dto.auth.LoginRequest
import com.taxiapp.server.dto.auth.LoginResponse
import com.taxiapp.server.dto.auth.MessageResponse
import com.taxiapp.server.dto.auth.RegisterDriverRequest
import com.taxiapp.server.dto.auth.SmsRequestDto
import com.taxiapp.server.dto.auth.SmsVerifyDto
import com.taxiapp.server.service.AuthService
import jakarta.validation.Valid
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.security.Principal

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val authService: AuthService
) {

    // --- ВХІД ---
    @PostMapping("/login")
    fun login(@RequestBody request: LoginRequest): LoginResponse {
        return authService.login(request)
    }

    // --- КЛІЄНТ: SMS Вхід/Реєстрація ---
    @PostMapping("/client/sms/request") 
    fun requestSms(@Valid @RequestBody request: SmsRequestDto): MessageResponse {
        return authService.requestSmsCode(request)
    }

    @PostMapping("/client/sms/verify")
    fun verifySms(@Valid @RequestBody request: SmsVerifyDto): LoginResponse {
        return authService.verifySmsCodeAndLogin(request)
    }

    // --- ВОДІЙ: Процес реєстрації ---

    // 1. Запит SMS для реєстрації водія
    @PostMapping("/driver/sms/request")
    fun requestDriverSms(@Valid @RequestBody request: SmsRequestDto): MessageResponse {
        return authService.requestDriverRegistrationSms(request)
    }

    // 2. Перевірка SMS (проміжний етап)
    @PostMapping("/driver/sms/verify-code")
    fun checkDriverSmsCode(@Valid @RequestBody request: SmsVerifyDto): MessageResponse {
        authService.checkDriverSmsCode(request.phoneNumber, request.code)
        return MessageResponse("Код вірний")
    }

    // 3. Фінальна реєстрація (MULTIPART)
    @PostMapping(value = ["/driver/register"], consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun registerDriver(
        @RequestPart("request") requestJson: String,
        @RequestPart("avatar", required = false) avatar: MultipartFile?,
        @RequestPart("driverLicenseFront", required = false) driverLicenseFront: MultipartFile?,
        @RequestPart("driverLicenseBack", required = false) driverLicenseBack: MultipartFile?,
        @RequestPart("techPassportFront", required = false) techPassportFront: MultipartFile?,
        @RequestPart("techPassportBack", required = false) techPassportBack: MultipartFile?,
        @RequestPart("insurance", required = false) insurance: MultipartFile?,
        @RequestPart("carFront", required = false) carFront: MultipartFile?,
        @RequestPart("carBack", required = false) carBack: MultipartFile?,
        @RequestPart("carLeft", required = false) carLeft: MultipartFile?,
        @RequestPart("carRight", required = false) carRight: MultipartFile?,
        @RequestPart("carInteriorFront", required = false) carInteriorFront: MultipartFile?,
        @RequestPart("carInteriorBack", required = false) carInteriorBack: MultipartFile?
    ): MessageResponse {
        // Десериализация JSON вручную
        val mapper = jacksonObjectMapper()
        val request = mapper.readValue(requestJson, RegisterDriverRequest::class.java)

        // Сбор файлов в Map
        val files = mutableMapOf<String, MultipartFile>()
        avatar?.let { files["avatar"] = it }
        driverLicenseFront?.let { files["driverLicenseFront"] = it }
        driverLicenseBack?.let { files["driverLicenseBack"] = it }
        techPassportFront?.let { files["techPassportFront"] = it }
        techPassportBack?.let { files["techPassportBack"] = it }
        insurance?.let { files["insurance"] = it }
        carFront?.let { files["carFront"] = it }
        carBack?.let { files["carBack"] = it }
        carLeft?.let { files["carLeft"] = it }
        carRight?.let { files["carRight"] = it }
        carInteriorFront?.let { files["carInteriorFront"] = it }
        carInteriorBack?.let { files["carInteriorBack"] = it }

        return authService.registerDriver(request, files)
    }

    // --- FCM TOKEN ---
    @PostMapping("/fcm-token")
    fun updateFcmToken(
        principal: Principal, 
        @RequestBody body: Map<String, String>
    ): ResponseEntity<Void> {
        val token = body["token"]
        if (token.isNullOrEmpty()) {
            return ResponseEntity.badRequest().build()
        }
        authService.updateFcmToken(principal.name, token)
        return ResponseEntity.ok().build()
    }
}