package com.taxiapp.server.controller

import com.taxiapp.server.dto.support.TelegramUpdate
import com.taxiapp.server.service.SupportService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/support/webhook")
class TelegramWebhookController(
    private val supportService: SupportService
) {
    @PostMapping
    fun receiveTelegramUpdate(@RequestBody update: TelegramUpdate): ResponseEntity<Void> {
        supportService.handleTelegramUpdate(update)
        return ResponseEntity.ok().build()
    }
}