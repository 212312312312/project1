package com.taxiapp.server.controller

import com.taxiapp.server.dto.driver.DriverDto
import com.taxiapp.server.dto.driver.UpdateDriverStatusRequest
import com.taxiapp.server.dto.driver.UpdateLocationRequest
import com.taxiapp.server.repository.DriverRepository
import com.taxiapp.server.service.DriverLocationService
import com.taxiapp.server.service.DriverService
import com.taxiapp.server.security.JwtUtils
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.*
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
    private val jwtUtils: JwtUtils
) {

    @Autowired
    private lateinit var messagingTemplate: SimpMessagingTemplate

    /**
     * Оновлення статусу (ОНЛАЙН/ОФЛАЙН)
     */
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

    /**
     * Оновлення поточної геолокації водія
     */
    @PostMapping("/location")
    fun updateLocation(
        @RequestBody request: UpdateLocationRequest,
        servletRequest: HttpServletRequest
    ): ResponseEntity<Void> {
        val authHeader = servletRequest.getHeader("Authorization")
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            val token = authHeader.substring(7)
            val driverId = jwtUtils.extractUserId(token)
            driverLocationService.updateLocation(driverId, request)
        }
        return ResponseEntity.ok().build()
    }

    /**
     * SOS СИГНАЛ
     */
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
            
            // --- ВИПРАВЛЕННЯ ТУТ: Додано ?: "Не вказано" ---
            phone = driver.userPhone ?: "Не вказано", 
            // -----------------------------------------------
            
            carNumber = driver.car?.plateNumber ?: "Без авто",
            lat = loc.lat,
            lng = loc.lng,
            timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        )

        // Відправляємо в адмінку через WebSocket
        messagingTemplate.convertAndSend("/topic/admin/sos", sosDto)
        
        return ResponseEntity.ok("SOS Sent")
    }

    /**
     * Отримання профілю водія
     */
    @GetMapping("/me")
    fun getDriverProfile(@AuthenticationPrincipal userDetails: UserDetails): ResponseEntity<DriverDto> {
        val username = userDetails.username
        val driver = (driverRepository.findByUserLogin(username) ?: driverRepository.findByUserPhone(username))
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Водія не знайдено")

        return ResponseEntity.ok(DriverDto(driver))
    }

    /**
     * Видалення водія з карти (вихід)
     */
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
}