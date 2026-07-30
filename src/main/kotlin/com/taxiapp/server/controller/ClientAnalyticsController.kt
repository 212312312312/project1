package com.taxiapp.server.controller

import com.taxiapp.server.dto.analytics.ClientEventBatchRequest
import com.taxiapp.server.service.AnalyticsService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.security.Principal

@RestController
@RequestMapping("/api/v1/client/analytics")
class ClientAnalyticsController(private val analyticsService: AnalyticsService) {

    @PostMapping("/events")
    fun collectEvents(
        @RequestBody request: ClientEventBatchRequest,
        principal: Principal
    ): ResponseEntity<Map<String, String>> {
        analyticsService.saveClientEvents(principal.name, request)
        return ResponseEntity.ok(mapOf("status" to "success", "message" to "Events recorded"))
    }
}