package com.taxiapp.server.controller

import com.taxiapp.server.dto.driver.ChartPointDto
import com.taxiapp.server.dto.driver.DriverStatsDto
import com.taxiapp.server.repository.TaxiOrderRepository
import com.taxiapp.server.security.JwtUtils
import jakarta.servlet.http.HttpServletRequest
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import com.taxiapp.server.repository.DriverRepository
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.time.LocalDateTime

@RestController
@RequestMapping("/api/v1/driver/stats")
class DriverStatsController(
    private val orderRepository: TaxiOrderRepository,
    private val driverRepository: DriverRepository,
    private val jwtUtils: JwtUtils
) {

    @GetMapping
    fun getStats(
        @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) fromDate: LocalDate,
        @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) toDate: LocalDate,
        servletRequest: HttpServletRequest
    ): ResponseEntity<DriverStatsDto> {
        val authHeader = servletRequest.getHeader("Authorization")

        // Получаем driverId через извлечение UUID
        val driverId = if (authHeader != null && authHeader.startsWith("Bearer ")) {
            val driverUuid = jwtUtils.extractUserUuid(authHeader.substring(7))
            val driver = driverRepository.findByUuid(driverUuid)
                .orElseThrow { ResponseStatusException(HttpStatus.UNAUTHORIZED, "Водій не знайдений") }
            driver.id
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

        // Временная хэш-карта для группировки чистого дохода по календарным дням
        val dailyCleanIncomeMap = mutableMapOf<LocalDate, Double>()

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

            // Вычисляем чистый доход за этот конкретный заказ (сумма минус 12% комиссии)
            val orderCommission = orderSum * COMMISSION_RATE
            val orderClean = orderSum - orderCommission

            // Определяем день: берем дату завершения заказа, либо дату создания, если заказ завершен некорректно
            val orderDate = o.completedAt?.toLocalDate() ?: o.createdAt.toLocalDate()

            // Плюсуем чистый доход к соответствующему дню в карте
            dailyCleanIncomeMap[orderDate] = dailyCleanIncomeMap.getOrDefault(orderDate, 0.0) + orderClean
        }

        val totalCommission = totalDirty * COMMISSION_RATE
        val totalClean = totalDirty - totalCommission

        val totalKm = totalDistMeters / 1000.0
        val totalHours = totalDurationSec / 3600.0
        val avgPrice = if (totalKm > 0) totalClean / totalKm else 0.0

        // Преобразуем хэш-карту в хронологически отсортированный список DTO для отправки на клиент
        val chartPointsList = dailyCleanIncomeMap.entries
            .sortedBy { it.key } // Сортируем даты от старых к новым
            .map { 
                ChartPointDto(
                    date = it.key.toString(), // Конвертирует в стандартный формат "yyyy-MM-dd"
                    income = it.value
                ) 
            }

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
                totalHours = totalHours,
                chartPoints = chartPointsList // Передаем честные точки на фронтенд
            )
        )
    }
}