package com.taxiapp.server.controller

import com.taxiapp.server.dto.analytics.GeneralAnalyticsResponse
import com.taxiapp.server.service.AnalyticsService
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/admin/analytics")
class AdminAnalyticsController(private val analyticsService: AnalyticsService) {

    @GetMapping("/general")
    @PreAuthorize("hasAnyAuthority('ADMINISTRATOR', 'ROLE_ADMINISTRATOR')")
    fun getGeneralAnalytics(): ResponseEntity<GeneralAnalyticsResponse> {
        return ResponseEntity.ok(analyticsService.getGeneralAnalytics())
    }
}