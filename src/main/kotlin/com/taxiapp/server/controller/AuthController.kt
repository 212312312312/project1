package com.taxiapp.server.controller

import com.taxiapp.server.dto.auth.LoginRequest
import com.taxiapp.server.dto.auth.LoginResponse
import com.taxiapp.server.dto.auth.MessageResponse
import com.taxiapp.server.dto.auth.RegisterDriverRequest
import com.taxiapp.server.dto.auth.SmsRequestDto
import com.taxiapp.server.dto.auth.SmsVerifyDto
import com.taxiapp.server.service.AuthService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity // <-- Додано
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.security.Principal // <-- Додано

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val authService: AuthService
) {

    // --- ВХІД ДЛЯ ВОДІЇВ/АДМІНІВ (Пароль) ---
    @PostMapping("/login")
    fun login(@RequestBody request: LoginRequest): LoginResponse {
        return authService.login(request)
    }

    // --- ВХІД ДЛЯ КЛІЄНТІВ (SMS) ---
    
    @PostMapping("/client/sms/request") 
    fun requestSms(@Valid @RequestBody request: SmsRequestDto): MessageResponse {
        return authService.requestSmsCode(request)
    }

    @PostMapping("/client/sms/verify")
    fun verifySms(@Valid @RequestBody request: SmsVerifyDto): LoginResponse {
        return authService.verifySmsCodeAndLogin(request)
    }

    // --- РЕЄСТРАЦІЯ ВОДІЯ (Адмінська) ---
    @PostMapping("/driver/register")
    fun registerDriver(@Valid @RequestBody request: RegisterDriverRequest): MessageResponse {
        return authService.registerDriver(request)
    }

    // --- НОВИЙ МЕТОД: Оновлення FCM Токена ---
    // Цей метод вимагає, щоб клієнт відправив Header "Authorization: Bearer <token>"
    @PostMapping("/fcm-token")
    fun updateFcmToken(
        principal: Principal, 
        @RequestBody body: Map<String, String>
    ): ResponseEntity<Void> {
        val token = body["token"] ?: return ResponseEntity.badRequest().build()
        
        // principal.name містить телефон або логін користувача з токена
        authService.updateFcmToken(principal.name, token)
        
        return ResponseEntity.ok().build()
    }
}