package com.taxiapp.server.controller

import com.taxiapp.server.dto.auth.LoginRequest
import com.taxiapp.server.dto.auth.LoginResponse
import com.taxiapp.server.dto.auth.MessageResponse
import com.taxiapp.server.dto.auth.RegisterDriverRequest
import com.taxiapp.server.dto.auth.SmsRequestDto
import com.taxiapp.server.dto.auth.SmsVerifyDto
import com.taxiapp.server.service.AuthService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

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
    
    // Важливо: шлях має співпадати з Android ApiService (@POST("auth/client/sms/request"))
    @PostMapping("/client/sms/request") 
    fun requestSms(@Valid @RequestBody request: SmsRequestDto): MessageResponse {
        return authService.requestSmsCode(request)
    }

    // Важливо: шлях має співпадати з Android ApiService (@POST("auth/client/sms/verify"))
    @PostMapping("/client/sms/verify")
    fun verifySms(@Valid @RequestBody request: SmsVerifyDto): LoginResponse {
        return authService.verifySmsCodeAndLogin(request)
    }

    // --- РЕЄСТРАЦІЯ ВОДІЯ (Адмінська) ---
    @PostMapping("/driver/register")
    fun registerDriver(@Valid @RequestBody request: RegisterDriverRequest): MessageResponse {
        return authService.registerDriver(request)
    }
}