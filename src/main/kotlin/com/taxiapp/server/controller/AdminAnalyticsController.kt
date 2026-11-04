package com.taxiapp.server.controller

import com.taxiapp.server.dto.analytics.CreateManualFinanceRequest
import com.taxiapp.server.dto.analytics.DeepAnalyticsResponse
import com.taxiapp.server.dto.analytics.GeneralAnalyticsResponse
import com.taxiapp.server.dto.analytics.UnitEconomicsDto
import com.taxiapp.server.service.AnalyticsService
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/admin/analytics")
class AdminAnalyticsController(private val analyticsService: AnalyticsService) {

    @GetMapping("/general")
    @PreAuthorize("hasAnyAuthority('ADMINISTRATOR', 'ROLE_ADMINISTRATOR', 'DISPATCHER', 'ROLE_DISPATCHER')")
    fun getGeneralAnalytics(
        @RequestParam(required = false, defaultValue = "ALL") period: String,
        @RequestParam(required = false) startDate: String?,
        @RequestParam(required = false) endDate: String?
    ): ResponseEntity<GeneralAnalyticsResponse> {
        return ResponseEntity.ok(analyticsService.getGeneralAnalytics(period, startDate, endDate))
    }

    @GetMapping("/deep")
    @PreAuthorize("hasAnyAuthority('ADMINISTRATOR', 'ROLE_ADMINISTRATOR', 'DISPATCHER', 'ROLE_DISPATCHER')")
    fun getDeepAnalytics(
        @RequestParam(required = false, defaultValue = "ALL") period: String,
        @RequestParam(required = false) startDate: String?,
        @RequestParam(required = false) endDate: String?
    ): ResponseEntity<DeepAnalyticsResponse> {
        return ResponseEntity.ok(analyticsService.getDeepAnalytics(period, startDate, endDate))
    }

    // Ручное добавление дохода (+) или ручной выплаты/расхода (-) в Чистый доход компании
    @PostMapping("/manual-finance")
    @PreAuthorize("hasAnyAuthority('ADMINISTRATOR', 'ROLE_ADMINISTRATOR', 'DISPATCHER', 'ROLE_DISPATCHER')")
    fun addManualFinanceEntry(
        @RequestBody request: CreateManualFinanceRequest
    ): ResponseEntity<UnitEconomicsDto> {
        return ResponseEntity.ok(analyticsService.addManualFinanceEntry(request))
    }

    @DeleteMapping("/manual-finance/{id}")
    @PreAuthorize("hasAnyAuthority('ADMINISTRATOR', 'ROLE_ADMINISTRATOR', 'DISPATCHER', 'ROLE_DISPATCHER')")
    fun deleteManualFinanceEntry(
        @PathVariable id: String
    ): ResponseEntity<UnitEconomicsDto> {
        return ResponseEntity.ok(analyticsService.deleteManualFinanceEntry(id))
    }
}