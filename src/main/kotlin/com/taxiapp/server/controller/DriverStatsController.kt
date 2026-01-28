package com.taxiapp.server.controller

import com.taxiapp.server.dto.driver.DriverStatsDto
import com.taxiapp.server.repository.TaxiOrderRepository
import com.taxiapp.server.security.JwtUtils
import jakarta.servlet.http.HttpServletRequest
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.LocalDateTime

@RestController
@RequestMapping("/api/v1/driver/stats")
class DriverStatsController(
    private val orderRepository: TaxiOrderRepository,
    private val jwtUtils: JwtUtils
) {

    @GetMapping
    fun getStats(
        @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) fromDate: LocalDate,
        @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) toDate: LocalDate,
        servletRequest: HttpServletRequest
    ): ResponseEntity<DriverStatsDto> {
        val authHeader = servletRequest.getHeader("Authorization")
        val driverId = if (authHeader != null && authHeader.startsWith("Bearer ")) {
            jwtUtils.extractUserId(authHeader.substring(7))
        } else {
            return ResponseEntity.status(401).build()
        }

        // Начало первого дня (00:00:00)
        val startOfPeriod = fromDate.atStartOfDay()
        // Конец последнего дня (23:59:59)
        val endOfPeriod = toDate.atTime(23, 59, 59)

        val orders = orderRepository.findCompletedOrdersForStats(driverId, startOfPeriod, endOfPeriod)

        var totalDirty = 0.0
        var incomeCard = 0.0
        var incomeCash = 0.0
        var incomeBalance = 0.0
        
        var totalDistMeters = 0
        var totalDurationSec = 0
        
        val COMMISSION_RATE = 0.12 

        for (o in orders) {
            val orderSum = o.price + o.addedValue
            totalDirty += orderSum

            when (o.paymentMethod) {
                "CASH" -> incomeCash += orderSum
                "CARD" -> incomeCard += orderSum
                else -> incomeBalance += orderSum
            }

            totalDistMeters += (o.distanceMeters ?: 0)
            totalDurationSec += (o.durationSeconds ?: 0)
        }

        val totalCommission = totalDirty * COMMISSION_RATE
        val totalClean = totalDirty - totalCommission

        val totalKm = totalDistMeters / 1000.0
        val totalHours = totalDurationSec / 3600.0
        val avgPrice = if (totalKm > 0) totalClean / totalKm else 0.0

        return ResponseEntity.ok(
            DriverStatsDto(
                totalIncome = totalClean,
                commission = totalCommission,
                incomeCard = incomeCard,
                incomeCash = incomeCash,
                incomeBalance = incomeBalance,
                ordersCount = orders.size,
                totalDistanceKm = totalKm,
                avgPricePerKm = avgPrice,
                totalHours = totalHours
            )
        )
    }
}